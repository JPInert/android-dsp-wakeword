// udkgen — build a Qualcomm SVA user-defined-keyword (UDK) sound model from N recordings, using the
// device's own libqvasml.so (from Qualcomm Voice Activation) + a UDK language model (default.lm). Neither file
// ships with this repo: see the README, "What you need to supply yourself".
// usage: udkgen <libqvasml.so> <default.lm> <keyword> <out.model> <rec1.raw> [rec2.raw ...]
// recordings: raw int16 PCM, 16 kHz, mono. Signatures from ListenSoundModelLib.h (LineageOS arpal-lx),
// cross-checked against the libqvasml symbols.
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

typedef struct { int16_t *data; uint32_t n_samples; } listen_user_recording;
typedef struct { uint8_t *data; uint32_t size; } listen_model_type;
typedef listen_model_type listen_language_model_type;
typedef int (*size_fn)(listen_model_type *, char *, char *, void *, uint32_t, listen_user_recording **,
                       listen_language_model_type *, uint32_t *);
typedef int (*create_fn)(listen_model_type *, char *, char *, void *, uint32_t, listen_user_recording **,
                         listen_language_model_type *, listen_model_type *, uint16_t *);

static uint8_t *slurp(const char *p, uint32_t *n) {
    FILE *f = fopen(p, "rb"); if (!f) { perror(p); exit(2); }
    fseek(f, 0, SEEK_END); long s = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc(s); if (fread(b, 1, s, f) != (size_t)s) { perror("read"); exit(2); }
    fclose(f); *n = (uint32_t)s; return b;
}

int main(int argc, char **argv) {
    if (argc < 6) { fprintf(stderr, "usage: udkgen lib lm keyword out rec...\n"); return 1; }
    void *h = dlopen(argv[1], RTLD_NOW); if (!h) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 2; }
    size_fn gsize = (size_fn)dlsym(h, "getUserDefinedKeywordSize");
    create_fn create = (create_fn)dlsym(h, "createUserDefinedKeywordModel");
    if (!gsize || !create) { fprintf(stderr, "dlsym failed\n"); return 2; }

    listen_language_model_type lm; lm.data = slurp(argv[2], &lm.size);
    int n = argc - 5;
    listen_user_recording *recs[16], r[16];
    for (int i = 0; i < n && i < 16; i++) {
        uint32_t bytes; r[i].data = (int16_t *)slurp(argv[5 + i], &bytes); r[i].n_samples = bytes / 2;
        recs[i] = &r[i];
        printf("rec %d: %u samples (%.2f s)\n", i, r[i].n_samples, r[i].n_samples / 16000.0);
    }
    char *kw = argv[3], user[] = "user";
    uint32_t outSize = 0;
    int st = gsize(NULL, kw, user, NULL, n, recs, &lm, &outSize);
    printf("getUserDefinedKeywordSize status=%d size=%u\n", st, outSize);
    if (st != 0 || !outSize) return 3;
    listen_model_type out = { malloc(outSize), outSize };
    uint16_t score = 0;
    st = create(NULL, kw, user, NULL, n, recs, &lm, &out, &score);
    printf("createUserDefinedKeywordModel status=%d size=%u matchingScore=%u\n", st, out.size, score);
    if (st != 0) return 4;
    FILE *f = fopen(argv[4], "wb"); fwrite(out.data, 1, out.size, f); fclose(f);
    printf("wrote %s\n", argv[4]);
    return 0;
}
