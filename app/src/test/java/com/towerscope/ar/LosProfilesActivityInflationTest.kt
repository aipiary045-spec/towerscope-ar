package com.towerscope.ar

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TowerScopeApp::class, qualifiers = "en-rUS")
class LosProfilesActivityInflationTest {

    @Test
    fun losProfilesActivity_onCreate_doesNotCrash() {
        Robolectric.buildActivity(LosProfilesActivity::class.java)
            .create()
            .start()
            .get()
    }
}
