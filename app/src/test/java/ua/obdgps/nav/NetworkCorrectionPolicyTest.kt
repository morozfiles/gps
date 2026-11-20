package ua.obdgps.nav

import org.junit.Assert.*
import org.junit.Test

class NetworkCorrectionPolicyTest {
    @Test fun independentPreciseNetworkFixGetsLargeCorrectionEvenAfter1kmDrift() {
        assertEquals(0.90,NetworkCorrectionPolicy.weight(20.0),0.00001)
        assertEquals(0.80,NetworkCorrectionPolicy.weight(60.0),0.00001)
    }
    @Test fun lowQualityNetworkFixGetsOnlyGentleCorrection() {
        assertEquals(0.65,NetworkCorrectionPolicy.weight(150.0),0.00001)
        assertEquals(0.45,NetworkCorrectionPolicy.weight(400.0),0.00001)
        assertEquals(0.25,NetworkCorrectionPolicy.weight(850.0),0.00001)
    }
    @Test fun invalidAccuracyIsNotTrusted() {
        for(a in listOf(-1.0,0.0,Double.NaN,Double.POSITIVE_INFINITY,1200.0))
            assertEquals(0.0,NetworkCorrectionPolicy.weight(a),0.00001)
    }
}
