// mm_codec.cpp — "mm-" API-key unwrap core for MiMoApp.
//
// Protocol (mm.transmute.v1) — MUST stay byte-identical with the owner-only
// encoder tools/mm_encode.ps1:
//
//   mm-<body>
//   body   = custom base64 of  blob        (alphabet = REVERSED standard b64,
//                                            standard '=' padding)
//   blob   = 0x01 || ct || mac8
//   ct     = utf8(sk-/tp- key) XOR keystream
//   keystream block i = SHA256(seed || BE32(i))   i = 0,1,2,... (32B blocks)
//   seed   = SHA256("mm.transmute.v1|seed|ZhiBai.keywrap.2026")
//   mackey = SHA256(seed || "mm.transmute.v1|mac|ZhiBai.keywrap.2026")
//   mac    = HMAC-SHA256(mackey, ct)[0..8)
//
// The unwrap lives in native code (libadb.so) on purpose: decompiling the APK
// only yields a one-line `external fun` — no Java-level implementation to read.
//
// Written C-style on purpose: this project builds with ANDROID_STL=none, so
// NO C++ runtime (no std::string/vector/exceptions) may be used here. Fixed
// buffers only; the longest accepted key is 1024 bytes.
//
// MM_CODEC_TEST_MAIN exposes a local CLI used to round-trip test this exact
// code against the PowerShell encoder during development.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace mm {

static const size_t MAX_PLAIN = 1024;
static const size_t MAX_BLOB = 1 + MAX_PLAIN + 8;
static const size_t MAX_B64 = ((MAX_BLOB + 2) / 3) * 4 + 4;

// ------------------------------------------------------------------ SHA-256

struct Sha256 {
    uint32_t h[8];
    uint8_t buf[64];
    uint64_t total;
    size_t buflen;

    static const uint32_t K[64];

    static uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

    void init() {
        h[0] = 0x6a09e667; h[1] = 0xbb67ae85; h[2] = 0x3c6ef372; h[3] = 0xa54ff53a;
        h[4] = 0x510e527f; h[5] = 0x9b05688c; h[6] = 0x1f83d9ab; h[7] = 0x5be0cd19;
        total = 0;
        buflen = 0;
    }

    void block(const uint8_t* p) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++) {
            w[i] = (uint32_t(p[i * 4]) << 24) | (uint32_t(p[i * 4 + 1]) << 16) |
                   (uint32_t(p[i * 4 + 2]) << 8) | uint32_t(p[i * 4 + 3]);
        }
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        uint32_t e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ (~e & g);
            uint32_t t1 = hh + S1 + ch + K[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + maj;
            hh = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    void update(const void* data, size_t len) {
        const uint8_t* p = static_cast<const uint8_t*>(data);
        total += len;
        while (len > 0) {
            size_t n = 64 - buflen;
            if (n > len) n = len;
            memcpy(buf + buflen, p, n);
            buflen += n;
            p += n;
            len -= n;
            if (buflen == 64) {
                block(buf);
                buflen = 0;
            }
        }
    }

    void finish(uint8_t out[32]) {
        uint64_t bits = total * 8;
        uint8_t pad = 0x80;
        update(&pad, 1);
        uint8_t zero = 0;
        while (buflen != 56) update(&zero, 1);
        uint8_t lenb[8];
        for (int i = 0; i < 8; i++) lenb[i] = uint8_t(bits >> (56 - i * 8));
        update(lenb, 8);
        for (int i = 0; i < 8; i++) {
            out[i * 4] = uint8_t(h[i] >> 24);
            out[i * 4 + 1] = uint8_t(h[i] >> 16);
            out[i * 4 + 2] = uint8_t(h[i] >> 8);
            out[i * 4 + 3] = uint8_t(h[i]);
        }
    }
};

const uint32_t Sha256::K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

static void sha256(const void* data, size_t len, uint8_t out[32]) {
    Sha256 s;
    s.init();
    s.update(data, len);
    s.finish(out);
}

// ------------------------------------------------------------------ HMAC

static void hmacSha256(const uint8_t* key, size_t keyLen,
                       const uint8_t* msg, size_t msgLen, uint8_t out[32]) {
    uint8_t k[64];
    memset(k, 0, sizeof(k));
    if (keyLen > 64) {
        sha256(key, keyLen, k);
    } else {
        memcpy(k, key, keyLen);
    }
    uint8_t ipad[64], opad[64];
    for (int i = 0; i < 64; i++) {
        ipad[i] = uint8_t(k[i] ^ 0x36);
        opad[i] = uint8_t(k[i] ^ 0x5c);
    }
    uint8_t inner[32];
    Sha256 si;
    si.init();
    si.update(ipad, 64);
    si.update(msg, msgLen);
    si.finish(inner);
    Sha256 so;
    so.init();
    so.update(opad, 64);
    so.update(inner, 32);
    so.finish(out);
}

// ------------------------------------------------------- custom base64

// Reverse of the standard alphabet — decoders in the wild won't recognize it.
static const char ALPHABET[] =
    "+/9876543210zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA";

static bool b64Decode(const char* in, size_t inLen, uint8_t* out, size_t outCap, size_t* outLen) {
    if (inLen == 0 || inLen % 4 != 0) return false;
    int table[256];
    for (int i = 0; i < 256; i++) table[i] = -1;
    for (int i = 0; i < 64; i++) table[uint8_t(ALPHABET[i])] = i;

    size_t n = 0;
    for (size_t i = 0; i < inLen; i += 4) {
        int v[4];
        int pad = 0;
        for (int j = 0; j < 4; j++) {
            char c = in[i + j];
            if (c == '=') {
                if (i + 4 != inLen || j < 2) return false; // pad only at end
                v[j] = 0;
                pad++;
            } else {
                if (pad > 0) return false; // data after padding
                int d = table[uint8_t(c)];
                if (d < 0) return false;
                v[j] = d;
            }
        }
        if (n + 3 > outCap) return false;
        out[n++] = uint8_t((v[0] << 2) | (v[1] >> 4));
        if (pad < 2) out[n++] = uint8_t(((v[1] & 0x0f) << 4) | (v[2] >> 2));
        if (pad < 1) out[n++] = uint8_t(((v[2] & 0x03) << 6) | v[3]);
    }
    *outLen = n;
    return true;
}

// ---------------------------------------------------------------- protocol

static const char SEED_SALT[] = "mm.transmute.v1|seed|ZhiBai.keywrap.2026";
static const char MAC_SALT[] = "mm.transmute.v1|mac|ZhiBai.keywrap.2026";

/**
 * Decode an mm- blob into out (NUL-terminated). Returns the plaintext length,
 * or 0 on ANY failure (bad prefix/base64/version/HMAC/plaintext shape).
 */
static size_t unwrap(const char* mm, char* out, size_t outCap) {
    const char prefix[] = "mm-";
    const size_t prefixLen = 3;
    if (mm == nullptr || strncmp(mm, prefix, prefixLen) != 0) return 0;
    const char* body = mm + prefixLen;
    size_t bodyLen = strlen(body);
    if (bodyLen == 0 || bodyLen > MAX_B64) return 0;

    uint8_t blob[MAX_BLOB];
    size_t blobLen = 0;
    if (!b64Decode(body, bodyLen, blob, sizeof(blob), &blobLen)) return 0;
    if (blobLen < 1 + 8 + 1) return 0;
    if (blob[0] != 0x01) return 0;

    const size_t ctLen = blobLen - 1 - 8;
    const uint8_t* ct = blob + 1;
    const uint8_t* mac = blob + 1 + ctLen;

    uint8_t seed[32];
    sha256(SEED_SALT, sizeof(SEED_SALT) - 1, seed);

    uint8_t mackeyIn[32 + sizeof(MAC_SALT) - 1];
    memcpy(mackeyIn, seed, 32);
    memcpy(mackeyIn + 32, MAC_SALT, sizeof(MAC_SALT) - 1);
    uint8_t mackey[32];
    sha256(mackeyIn, sizeof(mackeyIn), mackey);

    uint8_t mac2[32];
    hmacSha256(mackey, 32, ct, ctLen, mac2);
    uint8_t diff = 0;
    for (int i = 0; i < 8; i++) diff = uint8_t(diff | (mac[i] ^ mac2[i]));
    if (diff != 0) return 0;

    if (ctLen > MAX_PLAIN || ctLen + 1 > outCap) return 0;

    // keystream: SHA256(seed || BE32(blockIndex)) blocks, XOR in place
    uint8_t blockInput[36];
    memcpy(blockInput, seed, 32);
    for (size_t off = 0, idx = 0; off < ctLen; off += 32, idx++) {
        blockInput[32] = uint8_t(idx >> 24);
        blockInput[33] = uint8_t(idx >> 16);
        blockInput[34] = uint8_t(idx >> 8);
        blockInput[35] = uint8_t(idx);
        uint8_t ks[32];
        sha256(blockInput, 36, ks);
        for (size_t j = 0; j < 32 && off + j < ctLen; j++) {
            out[off + j] = char(ct[off + j] ^ ks[j]);
        }
    }
    out[ctLen] = '\0';

    if (ctLen < 8 || ctLen > 1000) return 0;
    if (strncmp(out, "sk-", 3) != 0 && strncmp(out, "tp-", 3) != 0) return 0;
    for (size_t i = 0; i < ctLen; i++) {
        unsigned char c = (unsigned char)out[i];
        if (c < 0x20 || c > 0x7E) return 0;
    }
    return ctLen;
}

}  // namespace mm

// ------------------------------------------------------------------- JNI

extern "C" JNIEXPORT jstring JNICALL
Java_com_zhibai_app_security_MmKeyCodec_nativeUnwrap(JNIEnv* env, jclass /*clazz*/, jstring jmm) {
    if (jmm == nullptr) return nullptr;
    const char* utf = env->GetStringUTFChars(jmm, nullptr);
    if (utf == nullptr) return nullptr;

    char out[1100];
    size_t n = mm::unwrap(utf, out, sizeof(out));
    env->ReleaseStringUTFChars(jmm, utf);
    if (n == 0) return nullptr;
    return env->NewStringUTF(out);
}

// ---------------------------------------------------------- local test CLI
// Build: cl /EHsc /DMM_CODEC_TEST_MAIN mm_codec.cpp /Fe:mm_test.exe
#ifdef MM_CODEC_TEST_MAIN
int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: mm_test <mm-blob>\n");
        return 2;
    }
    char out[1100];
    size_t n = mm::unwrap(argv[1], out, sizeof(out));
    if (n == 0) {
        fprintf(stderr, "UNWRAP_FAILED\n");
        return 1;
    }
    fputs(out, stdout);
    fputc('\n', stdout);
    return 0;
}
#endif
