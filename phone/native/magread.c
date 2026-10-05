// Print the raw (uncalibrated) magnetometer once: "anom=<|raw-bias| µT> raw=<|raw|> bias=<|bias|> x y z".
// anom = the field minus the phone's own magnetism: ~50 µT loose (Earth), ~90 µT on my desk MagSafe charger (one dock/undock cycle).
// Raw, not calibrated: the phone's auto-calibration learns a dock magnet as hard-iron bias and hides it.
// Used by on-wake.sh at fire time to tell "on a MagSafe mount" from "loose". Usage: magread [samples]
#include <android/sensor.h>
#include <android/looper.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    int want = argc > 1 ? atoi(argv[1]) : 5;
    ASensorManager *sm = ASensorManager_getInstanceForPackage("stwake");
    const ASensor *s = ASensorManager_getDefaultSensor(sm, ASENSOR_TYPE_MAGNETIC_FIELD_UNCALIBRATED);
    if (!s) { fprintf(stderr, "no uncalibrated magnetometer\n"); return 2; }
    ALooper *lp = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    ASensorEventQueue *q = ASensorManager_createEventQueue(sm, lp, 1, NULL, NULL);
    ASensorEventQueue_registerSensor(q, s, 20000, 0);
    double x = 0, y = 0, z = 0, bx = 0, by = 0, bz = 0; int n = 0, skip = 2, tries = 0;
    while (n < want && tries++ < 100) {
        ALooper_pollOnce(50, NULL, NULL, NULL);
        ASensorEvent e;
        while (n < want && ASensorEventQueue_getEvents(q, &e, 1) > 0) {
            if (skip > 0) { skip--; continue; }          // first samples after enable can be stale
            x += e.uncalibrated_magnetic.x_uncalib; y += e.uncalibrated_magnetic.y_uncalib;
            z += e.uncalibrated_magnetic.z_uncalib;
            bx += e.uncalibrated_magnetic.x_bias; by += e.uncalibrated_magnetic.y_bias;
            bz += e.uncalibrated_magnetic.z_bias; n++;
        }
    }
    ASensorEventQueue_disableSensor(q, s);
    if (!n) { fprintf(stderr, "no events\n"); return 3; }
    x /= n; y /= n; z /= n; bx /= n; by /= n; bz /= n;
    double ax = x - bx, ay = y - by, az = z - bz;
    printf("anom=%.0f raw=%.0f bias=%.0f %.1f %.1f %.1f\n", sqrt(ax*ax + ay*ay + az*az), sqrt(x*x + y*y + z*z), sqrt(bx*bx + by*by + bz*bz), x, y, z);
    return 0;
}
