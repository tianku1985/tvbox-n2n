#ifdef __linux__

/*
 * Android 专用 tuntap：
 * Android VpnService 提供的是 L3 的 TUN 设备，而 n2n edge 走的是 TAP（以太网帧）。
 * 本文件用一个后台线程把 TUN 的 IP 包封装成以太网帧（含 ARP 模拟与 ARP 缓存），
 * 再经管道交给 edge 主循环读取；edge 写回的帧在这里去掉以太网头后写入 TUN。
 *
 * 文件描述符约定：
 *   - 优先读取环境变量 N2N_TUN_FD 指定的 fd；
 *   - 缺省回退到 stdin(fd 0)，由上层以 stdin=VPN TUN fd 的方式启动 edge。
 */

#include "n2n.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define ETH_HDR_LEN       14                  /* 以太网头长度 */
#define ETH_PTYPE_IPV4    0x0800              /* 以太类型: IPv4 */
#define ETH_PTYPE_ARP     0x0806              /* 以太类型: ARP */
#define ETH_PTYPE_IPV6    0x86DD              /* 以太类型: IPv6 */
#define ARP_OP_REQUEST    0x0001              /* ARP 请求 */
#define ARP_OP_REPLY      0x0002              /* ARP 应答 */
#define N2N_TUN_FD_ENV    "N2N_TUN_FD"        /* 环境变量名 */

#define ARP_CACHE_SIZE    64                  /* ARP 缓存条目数 */
#define ARP_CACHE_TTL     120                 /* 条目有效期(秒) */
#define ARP_RETRY_SEC     1                   /* 同一目标 IP 的 ARP 重试间隔(秒) */

/* N2N_PKT_BUF_SIZE(2048) 的 IP 帧还要加上 14 字节以太网头 */
#define TUN_MAX_ETH       2112
#define TUN_QUEUE_SIZE    64                  /* 环形队列帧数 */

typedef struct {
    n2n_mac_t mac;
    uint8_t   ipv4[4];
    time_t    last_seen;
} arp_entry_t;

typedef struct {
    uint8_t data[TUN_MAX_ETH];
    uint16_t len;
} tun_frame_t;

static pthread_mutex_t tun_mutex = PTHREAD_MUTEX_INITIALIZER;
static arp_entry_t arp_cache[ARP_CACHE_SIZE];
static n2n_mac_t arp_our_mac;                 /* 本机 MAC(自动/手动生成) */
static uint8_t arp_our_ip[4];
static uint32_t arp_our_ip32;
static uint32_t arp_our_mask32;
static uint8_t arp_pending_ip[4];             /* 正在等待 ARP 应答的远端 IP */
static int arp_pending_set;
static time_t arp_pending_time;

/* 帧环形队列: 满时覆盖最旧一帧 */
static tun_frame_t tun_queue[TUN_QUEUE_SIZE];
static int tun_queue_head;                    /* 下一个写入位置 */
static int tun_queue_count;

static int tun_fd = -1;                       /* VpnService 提供的 TUN fd */
static int tun_pipe_r = -1;                   /* 管道读端 = edge 眼中的 device->fd */
static int tun_pipe_w = -1;                   /* 管道写端, 桥接线程写入 */
static int tun_bridge_running;
static pthread_t tun_bridge_thread;

static int same_ipv4 (const uint8_t *a, const uint8_t *b) {
    return memcmp(a, b, 4) == 0;
}

static int is_zero_ipv4 (const uint8_t *a) {
    return (a[0] | a[1] | a[2] | a[3]) == 0;
}

static int is_unicast_mac (const uint8_t *m) {
    return !is_broadcast(m) && !is_multi_broadcast(m);
}

static void eth_set_mac (uint8_t *dst, const n2n_mac_t mac) {
    memcpy(dst, mac, N2N_MAC_SIZE);
}

/* 组装以太网头: 目的 MAC + 本机 MAC + 以太类型 */
static void eth_build_header (uint8_t *frame, const uint8_t *dst_mac, uint16_t ptype) {
    eth_set_mac(frame, dst_mac);
    eth_set_mac(frame + 6, arp_our_mac);
    frame[12] = ptype >> 8;
    frame[13] = ptype & 0xFF;
}

/* 判断目标 IP 是否为广播/组播(IPv4 专用) */
static int eth_is_broadcast_dst (const uint8_t *dst_ipv4) {
    uint32_t d;
    memcpy(&d, dst_ipv4, 4);
    if ((uint8_t)dst_ipv4[0] >= 224)          /* 组播 */
        return 1;
    if ((uint8_t)dst_ipv4[0] == 255)          /* 受限广播 255.255.255.255 */
        return 1;
    if ((d & arp_our_mask32) == (arp_our_ip32 & arp_our_mask32) /* 定向广播 */
        && (d | ~arp_our_mask32) == (uint32_t)0xFFFFFFFFU)
        return 1;
    return 0;
}

/* 学习 IP->MAC 映射(来自 ARP 帧或 IP 帧源地址) */
static void arp_cache_learn (const uint8_t *ipv4, const uint8_t *mac) {
    int i;
    int oldest = 0;
    time_t now = time(NULL);

    if (is_zero_ipv4(ipv4) || is_broadcast(mac) || !is_unicast_mac(mac))
        return;
    if (same_ipv4(ipv4, arp_our_ip))
        return;

    pthread_mutex_lock(&tun_mutex);
    for(i = 0; i < ARP_CACHE_SIZE; i++) {
        if(arp_cache[i].last_seen && same_ipv4(arp_cache[i].ipv4, ipv4)) {
            eth_set_mac(arp_cache[i].mac, mac);
            arp_cache[i].last_seen = now;
            pthread_mutex_unlock(&tun_mutex);
            return;
        }
    }
    for(i = 0; i < ARP_CACHE_SIZE; i++) {
        if(!arp_cache[i].last_seen) {
            eth_set_mac(arp_cache[i].mac, mac);
            memcpy(arp_cache[i].ipv4, ipv4, 4);
            arp_cache[i].last_seen = now;
            pthread_mutex_unlock(&tun_mutex);
            return;
        }
        if(arp_cache[i].last_seen < arp_cache[oldest].last_seen)
            oldest = i;
    }
    eth_set_mac(arp_cache[oldest].mac, mac);  /* 表已满, 淘汰最旧 */
    memcpy(arp_cache[oldest].ipv4, ipv4, 4);
    arp_cache[oldest].last_seen = now;
    pthread_mutex_unlock(&tun_mutex);
}

/* 查询 IP->MAC, 命中则返回 1 */
static int arp_cache_lookup (const uint8_t *ipv4, n2n_mac_t mac_out) {
    int i;
    time_t now = time(NULL);

    pthread_mutex_lock(&tun_mutex);
    for(i = 0; i < ARP_CACHE_SIZE; i++) {
        if(arp_cache[i].last_seen
           && (now - arp_cache[i].last_seen) < ARP_CACHE_TTL
           && same_ipv4(arp_cache[i].ipv4, ipv4)) {
            eth_set_mac(mac_out, arp_cache[i].mac);
            pthread_mutex_unlock(&tun_mutex);
            return 1;
        }
    }
    pthread_mutex_unlock(&tun_mutex);
    return 0;
}

/* 收到对端 ARP 应答后, 清除等待标记 */
static void arp_cache_clear_pending (const uint8_t *ipv4) {
    pthread_mutex_lock(&tun_mutex);
    if(arp_pending_set && same_ipv4(arp_pending_ip, ipv4))
        arp_pending_set = 0;
    pthread_mutex_unlock(&tun_mutex);
}

/* 入队一个以太网帧, 并通过管道唤醒 edge 主循环 */
static void tun_enqueue (const uint8_t *frame, size_t len) {
    unsigned char sig = 1;

    if(tun_pipe_w < 0)
        return;
    if(len > TUN_MAX_ETH)
        return;
    pthread_mutex_lock(&tun_mutex);
    memcpy(tun_queue[tun_queue_head].data, frame, len);
    tun_queue[tun_queue_head].len = (uint16_t)len;
    tun_queue_head = (tun_queue_head + 1) % TUN_QUEUE_SIZE;
    if(tun_queue_count < TUN_QUEUE_SIZE)
        tun_queue_count++;                    /* 队列满时直接覆盖最旧一帧 */
    pthread_mutex_unlock(&tun_mutex);
    if(write(tun_pipe_w, &sig, 1) < 0) {
        /* 写失败(管道空/被关闭)时忽略, 由上层链路兜底 */
    }
}

/* 出队一帧(edge 主循环调用); 先消费对应的一字节唤醒信号 */
static int tun_dequeue (uint8_t *buf, int len) {
    unsigned char sig;
    int tail;

    while(read(tun_pipe_r, &sig, 1) != 1) {
        if(errno != EINTR)
            return -1;
    }
    pthread_mutex_lock(&tun_mutex);
    if(tun_queue_count > 0) {
        tail = (tun_queue_head - tun_queue_count + TUN_QUEUE_SIZE) % TUN_QUEUE_SIZE;
        if(tun_queue[tail].len <= (uint16_t)len) {
            memcpy(buf, tun_queue[tail].data, tun_queue[tail].len);
            tun_queue_count--;
            pthread_mutex_unlock(&tun_mutex);
            return tun_queue[tail].len;
        }
    }
    pthread_mutex_unlock(&tun_mutex);
    return -1;
}

/* 构造 ARP 请求(42 字节) */
static int tun_build_arp_request (uint8_t *frame, const uint8_t *target_ip) {
    eth_build_header(frame, broadcast_mac, ETH_PTYPE_ARP);
    frame[14] = 0x00; frame[15] = 0x01;       /* 硬件类型: 以太网 */
    frame[16] = 0x08; frame[17] = 0x00;       /* 协议类型: IP */
    frame[18] = 0x06; frame[19] = 0x04;       /* 硬件/协议地址长度 */
    frame[20] = 0x00; frame[21] = 0x01;       /* 操作码: 请求 */
    eth_set_mac(frame + 22, arp_our_mac);     /* 发送方 MAC */
    memcpy(frame + 28, arp_our_ip, 4);        /* 发送方 IP */
    memset(frame + 32, 0, 6);                 /* 目标 MAC 未知 */
    memcpy(frame + 38, target_ip, 4);         /* 目标 IP */
    return 42;
}

/* 把 TUN 读到的 IP 包封装为以太网帧; 未知目的 MAC 时发 ARP 请求并丢弃该 IP 包 */
static int tun_wrap_ip_packet (const uint8_t *ip, size_t ip_len, uint8_t *out) {
    n2n_mac_t dst_mac;
    int version;

    if(ip_len < 20)
        return -1;
    version = ip[0] >> 4;
    if(version == 4) {
        if(is_zero_ipv4(ip + 16))             /* 空目的地址的怪包直接丢弃 */
            return -1;
        if(eth_is_broadcast_dst(ip + 16)) {
            eth_build_header(out, broadcast_mac, ETH_PTYPE_IPV4);
        } else if(arp_cache_lookup(ip + 16, dst_mac)) {
            eth_build_header(out, dst_mac, ETH_PTYPE_IPV4);
        } else {
            /* 目的 MAC 未知: 发 ARP 请求, 限速重试 */
            time_t now = time(NULL);
            pthread_mutex_lock(&tun_mutex);
            if(arp_pending_set && same_ipv4(arp_pending_ip, ip + 16)
               && (now - arp_pending_time) < ARP_RETRY_SEC) {
                pthread_mutex_unlock(&tun_mutex);
                return -1;
            }
            memcpy(arp_pending_ip, ip + 16, 4);
            arp_pending_set = 1;
            arp_pending_time = now;
            pthread_mutex_unlock(&tun_mutex);
            return tun_build_arp_request(out, ip + 16);
        }
    } else if(version == 6) {
        /* 仅放行组播(目的地址首字节 0xFF)IPv6 帧 */
        if(ip_len >= 28 && ip[24] != 0xFF)
            return -1;
        eth_build_header(out, broadcast_mac, ETH_PTYPE_IPV6);
    } else {
        return -1;
    }
    memcpy(out + ETH_HDR_LEN, ip, ip_len);
    return (int)ip_len + ETH_HDR_LEN;
}

/* 桥接线程: 轮询 TUN, 读取 IP 包并入队 */
static void *tun_bridge_main (void *arg) {
    uint8_t ip[TUN_MAX_ETH];
    uint8_t frame[TUN_MAX_ETH];
    struct pollfd pfd;
    ssize_t n;
    int flen;

    while(tun_bridge_running) {
        pfd.fd = tun_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        if(poll(&pfd, 1, 50) < 0) {
            if(errno == EINTR)
                continue;
            break;
        }
        if(tun_bridge_running == 0)
            break;
        if(!(pfd.revents & POLLIN))
            continue;
        n = read(tun_fd, ip, TUN_MAX_ETH);
        if(n <= 0) {
            if(errno == EINTR)
                continue;
            break;
        }
        flen = tun_wrap_ip_packet(ip, (size_t)n, frame);
        if(flen > 0)
            tun_enqueue(frame, (size_t)flen);
    }
    return NULL;
}

static int tun_start_bridge (void) {
    int pipes[2];

    if(tun_pipe_r >= 0 || tun_pipe_w >= 0)
        return -1;
    if(pipe(pipes) < 0)
        return -1;
    tun_pipe_r = pipes[0];
    tun_pipe_w = pipes[1];
    tun_bridge_running = 1;
    if(pthread_create(&tun_bridge_thread, NULL, tun_bridge_main, NULL) != 0) {
        close(tun_pipe_r);
        close(tun_pipe_w);
        tun_pipe_r = -1;
        tun_pipe_w = -1;
        tun_bridge_running = 0;
        return -1;
    }
    return 0;
}

static void tun_stop_bridge (void) {
    if(tun_bridge_running) {
        tun_bridge_running = 0;
        pthread_join(tun_bridge_thread, NULL);
    }
    if(tun_pipe_r >= 0) {
        close(tun_pipe_r);
        tun_pipe_r = -1;
    }
    if(tun_pipe_w >= 0) {
        close(tun_pipe_w);
        tun_pipe_w = -1;
    }
}

int tuntap_open (tuntap_dev *device, char *dev, const char *address_mode, char *device_ip,
                 char *device_mask, const char *device_mac, int mtu, int metric) {
    const char *env_fd;
    int fd = -1;

    if(tun_fd >= 0 || tun_bridge_running)
        tun_stop_bridge();

    env_fd = getenv(N2N_TUN_FD_ENV);
    if(env_fd)
        fd = atoi(env_fd);
    if(fd >= 0 && fcntl(fd, F_GETFD) < 0)     /* fd 不可用则回退 stdin */
        fd = -1;
    if(fd < 0)
        fd = 0;
    tun_fd = fd;

    memset(tun_queue, 0, sizeof(tun_queue));
    tun_queue_head = 0;
    tun_queue_count = 0;
    memset(arp_cache, 0, sizeof(arp_cache));
    arp_pending_set = 0;

    memset(device, 0, sizeof(*device));
    if(device_mac && device_mac[0])
        str2mac(arp_our_mac, (char *)device_mac);
    else {
        memrnd(arp_our_mac, N2N_MAC_SIZE);    /* 随机生成本地管理 MAC */
        arp_our_mac[0] &= ~0x01;
        arp_our_mac[0] |= 0x02;
    }
    eth_set_mac(device->mac_addr, arp_our_mac);

    device->ip_addr = inet_addr(device_ip);
    device->device_mask = inet_addr(device_mask);
    device->mtu = (uint16_t)mtu;
    if(dev)
        strncpy(device->dev_name, dev, N2N_IFNAMSIZ - 1);

    memcpy(arp_our_ip, &(device->ip_addr), 4);
    arp_our_ip32 = device->ip_addr;
    arp_our_mask32 = device->device_mask;

    if(tun_start_bridge() < 0) {
        tun_fd = -1;
        return -1;
    }
    device->fd = tun_pipe_r;
    return device->fd;
}

int tuntap_read (struct tuntap_dev *tuntap, unsigned char *buf, int len) {
    return tun_dequeue(buf, len);
}

int tuntap_write (struct tuntap_dev *tuntap, unsigned char *buf, int len) {
    uint16_t ptype;
    const uint8_t *arp;
    uint16_t op;

    if(len < ETH_HDR_LEN)
        return len;

    ptype = (uint16_t)((buf[12] << 8) | buf[13]);

    if(ptype == ETH_PTYPE_ARP) {
        if(len < 42)
            return len;
        arp = buf + ETH_HDR_LEN;
        op = (uint16_t)((arp[6] << 8) | arp[7]);      /* 操作码 */
        arp_cache_learn(arp + 14, arp + 8);           /* SPA -> SHA 学习 */
        arp_cache_clear_pending(arp + 14);
        if(op == ARP_OP_REQUEST && same_ipv4(arp + 24, arp_our_ip)) {
            uint8_t reply[42];
            memcpy(reply, buf, 42);
            eth_set_mac(reply, buf + 6);              /* 目的 = 请求方 */
            eth_set_mac(reply + 6, arp_our_mac);      /* 源 = 本机 */
            reply[20] = 0x00; reply[21] = 0x02;       /* 操作码: 应答 */
            eth_set_mac(reply + 22, arp_our_mac);
            memcpy(reply + 28, arp_our_ip, 4);
            eth_set_mac(reply + 32, buf + 6);
            memcpy(reply + 38, arp + 14, 4);
            tun_enqueue(reply, 42);                   /* 应答直接回给内核 */
        }
        return len;
    }

    if(ptype == ETH_PTYPE_IPV4 || ptype == ETH_PTYPE_IPV6) {
        /* 只处理广播/组播/发给本机 MAC 的帧 */
        if(len > ETH_HDR_LEN
           && (is_broadcast(buf) || is_multi_broadcast(buf) || memcmp(buf, arp_our_mac, N2N_MAC_SIZE) == 0)) {
            if(ptype == ETH_PTYPE_IPV4 && len >= 34)
                arp_cache_learn(buf + 14 + 12, buf + 6);  /* IP 源地址学习 MAC */
            if(write(tun_fd, buf + ETH_HDR_LEN, len - ETH_HDR_LEN) < 0) {
                /* TUN 写入失败时忽略 */
            }
        }
        return len;
    }

    return len;
}

void tuntap_close (struct tuntap_dev *tuntap) {
    tun_stop_bridge();
}

void tuntap_get_address (struct tuntap_dev *tuntap) {
}

#endif