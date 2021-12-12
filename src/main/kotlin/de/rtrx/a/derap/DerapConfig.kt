package de.rtrx.a.derap

import com.uchuhimo.konf.ConfigSpec

object DerapConfig : ConfigSpec("derap") {

    val linkFlairClass by required<String>()
}