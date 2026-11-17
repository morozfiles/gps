# Diagnostic dual tracks and Lima GPS-spoof quarantine

Version 0.10.2-alpha implements two independent test map traces.

## Test map

Open **Тестовая карта · DR** and tap **Начать два трека** while the vehicle is parked with a phone rigidly attached to its holder. The navigation service must have a trustworthy initial position and bearing (manual start or accepted recent GPS/heading). A one-time absolute start is necessary to draw latitude/longitude; no absolute coordinates can be derived from accelerometers alone.

- **Green**: only real `LocationManager.GPS_PROVIDER` observations. Rejected GPS readings are shown in **orange**, *never fed into navigation*. Long/implausible segments are broken rather than drawing a straight line from Ukraine to Peru.
- **Blue**: a separate pure-inertial estimate initialized with the starting coordinate and orientation, and then updated **only** from `TYPE_GYROSCOPE` and `TYPE_LINEAR_ACCELERATION` (gravity determines vertical yaw axis). It does not read live GPS, OBD/Bluetooth speed, Wi-Fi, cellular data, or fused locations after start. It estimates speed by integrating acceleration. The first two seconds must be motionless for bias sampling.
- The existing OBD-assisted DR with trusted network/GPS corrections is unaffected and remains the primary navigation fallback; it is *not* the blue test trace.
- Tracks are kept in RAM and reset when starting a new test. No persistent trace export yet.

**Limitations:** accelerometer-only speed and heading integration accumulate errors rapidly. The blue line may drift significantly, including when stopped; it is **not** a substitute for OBD speed and validated positioning.

## GPS anomaly handling

- GPS coordinates within a broad Lima, Peru bounding region are always rejected for navigation. This is a trip-specific heuristic for this Ukraine-oriented app, **not a proof of spoofing**.
- A physically impossible GPS jump from an earlier accepted fix is quarantined. Existing OBD-vs-GPS speed disagreement checks remain active.
- Suspicious GPS coordinates are never used to re-seed the live DeadReckoner, correct its position, or align the heading.
- During quarantine, the primary navigation can switch to OBD-assisted DR if an accepted initial anchor and recent OBD data are available.
- Corrections use bounded network fixes from Android `NETWORK_PROVIDER`. These typically use Wi-Fi and cellular networks. Bluetooth-based beacon positioning depends on device, platform services and a surveyed beacon database and **cannot be guaranteed**.
- Fused provider updates are blocked from DR correction during quarantine because they may include spoofed GNSS.
- A quarantine exit requires reported GPS speed ≤180 km/h, a position outside the Lima region, proximity to a recent independent network/DR anchor and at least **five** spaced, agreeing samples. A speed drop alone never re-enables GPS.

## Test checklist

1. Start the app with GPS and sensor permissions, confirm GPS/OBD or set a manual start point and direction.
2. Park the vehicle and start both tracks. Stay still 2 seconds.
3. Drive normally. Blue and green tracks should be independent and separate.
4. If a physical GPS spoof moves to Lima: its **raw GPS point** appears as rejected, the map must not jump, and the primary navigation should use DR/network if safe inputs exist.
5. Even if the spoofed speed falls below 180 km/h, Lima fixes must remain rejected.
6. Leave the quarantine only after several consistent, independently corroborated, non-Lima fixes. Never turn off checks solely based on speed.
7. Repeat with no OBD available: the system must **not invent a DR track** or seed it from suspicious GNSS.

**Safety:** requires actual stationary and road tests with trace logging. A successful APK compilation does not verify GNSS spoof resistance or route accuracy. Do not depend on this experimental feature alone for critical navigation.
