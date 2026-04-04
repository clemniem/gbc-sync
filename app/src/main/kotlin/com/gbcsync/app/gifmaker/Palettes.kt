package com.gbcsync.app.gifmaker

data class GbPalette(
    val shortName: String,
    val name: String,
    val colors: List<Int>,
) {
    companion object {
        private fun hex(color: String): Int {
            val c = color.removePrefix("#")
            return 0xFF000000.toInt() or c.toLong(16).toInt()
        }

        private fun p(shortName: String, name: String, c1: String, c2: String, c3: String, c4: String) =
            GbPalette(shortName, name, listOf(hex(c1), hex(c2), hex(c3), hex(c4)))

        /** "Original" — use the image's own colors, no remapping. */
        val ORIGINAL = GbPalette("original", "Original (from image)", emptyList())

        val ALL: List<GbPalette> = listOf(
            ORIGINAL,
            p("bw", "Black & White", "#ffffff", "#aaaaaa", "#555555", "#000000"),
            p("dmg", "Original Game Boy", "#9bbc0f", "#77a112", "#306230", "#0f380f"),
            p("gbpocket", "Game Boy Pocket", "#c4cfa1", "#8b956d", "#4d533c", "#1f1f1f"),
            p("bgb", "BGB Emulator", "#e0f8d0", "#88c070", "#346856", "#081820"),
            p("gbli", "Game Boy Light", "#1ddece", "#19c7b3", "#16a596", "#0b7a6d"),
            p("grafixkidgray", "Grafixkid Gray", "#e0dbcd", "#a89f94", "#706b66", "#2b2b26"),
            p("grafixkidgreen", "Grafixkid Green", "#dbf4b4", "#abc396", "#7b9278", "#4c625a"),
            p("gbceuus", "GBC Camera EU/US", "#ffffff", "#7bff31", "#0063c5", "#000000"),
            p("gbcjp", "PocketCamera JP", "#ffffff", "#ffce00", "#9c6300", "#000000"),
            p("gbcu", "GBC Splash Up", "#ffffff", "#ffad63", "#843100", "#000000"),
            p("gbcua", "GBC Splash Up+A", "#ffffff", "#ff8484", "#943a3a", "#000000"),
            p("gbcub", "GBC Splash Up+B", "#ffe6c5", "#ce9c84", "#846b29", "#5a3108"),
            p("gbcd", "GBC Splash Down", "#ffffa5", "#ff9494", "#9494ff", "#000000"),
            p("gbcda", "GBC Splash Down+A", "#ffffff", "#ffff00", "#ff0000", "#000000"),
            p("gbcdb", "GBC Splash Down+B", "#ffffff", "#ffff00", "#7b4a00", "#000000"),
            p("gbcl", "GBC Splash Left", "#ffffff", "#63a5ff", "#0000ff", "#000000"),
            p("gbcla", "GBC Splash Left+A", "#ffffff", "#8c8cde", "#52528c", "#000000"),
            p("gbclb", "GBC Splash Left+B", "#ffffff", "#a5a5a5", "#525252", "#000000"),
            p("gbcr", "GBC Splash Right", "#ffffff", "#52ff00", "#ff4200", "#000000"),
            p("gbcrb", "GBC Splash Right+B", "#000000", "#008484", "#ffde00", "#ffffff"),
            p("blackzero", "Game Boy (Black Zero)", "#7e8416", "#577b46", "#385d49", "#2e463d"),
            p("hipster", "Artistic Caffeinated Lactose", "#fdfef5", "#dea963", "#9e754f", "#241606"),
            p("d2kr", "Dune 2000 remastered", "#fbf1cd", "#c09e7d", "#725441", "#000000"),
            p("roga", "Romero's Garden", "#ebc4ab", "#649a57", "#574431", "#323727"),
            p("llawk", "Links late Awakening", "#ffffb5", "#7bc67b", "#6b8c42", "#5a3921"),
            p("shmgy", "Super Hyper Mega Gameboy", "#f7e7c6", "#d68e49", "#a63725", "#331e50"),
            p("chig", "Childhood in Greenland", "#d0d058", "#a0a840", "#708028", "#405010"),
            p("dhg", "Deep Haze Green", "#a1d909", "#467818", "#27421f", "#000000"),
            p("azc", "Azure Clouds", "#47ff99", "#32b66d", "#124127", "#000000"),
            p("cybl", "Cyanide Blues", "#9efbe3", "#21aff5", "#1e4793", "#0e1e3d"),
            p("wtfp", "Waterfront Plaza", "#cecece", "#6f9edf", "#42678e", "#102533"),
            p("tsk", "The starry knight", "#f5db37", "#37cae5", "#0f86b6", "#123f77"),
            p("ffs", "Flowerfeldstrasse", "#e9d9cc", "#c5c5ce", "#75868f", "#171f62"),
            p("datn", "Drowning at night", "#a9b0b3", "#586164", "#20293f", "#030c22"),
            p("glmo", "Glowing Mountains", "#ffbf98", "#a1a8b8", "#514f6c", "#2f1c35"),
            p("shzol", "Space Haze Overload", "#f8e3c4", "#cc3495", "#6b1fb1", "#0b0630"),
            p("slmem", "Starlit Memories", "#869ad9", "#6d53bd", "#6f2096", "#4f133f"),
            p("tpa", "Tramonto al Parco", "#f3c677", "#e64a4e", "#912978", "#0c0a3e"),
            p("rcs", "Rusted City Sign", "#edb4a1", "#a96868", "#764462", "#2c2137"),
            p("ppr", "Purple Rain", "#adfffc", "#8570b2", "#ff0084", "#68006a"),
            p("cctr", "Candy Cotton Tower Raid", "#e6aec4", "#e65790", "#8f0039", "#380016"),
            p("cfp", "Caramel Fudge Paranoia", "#cf9255", "#cf7163", "#b01553", "#3f1711"),
            p("dimwm", "Dies ist meine Wassermelone", "#ffdbcb", "#f27d7a", "#558429", "#222903"),
            p("fsil", "Floyd Steinberg in Love", "#eaf5fa", "#5fb1f5", "#d23c4e", "#4c1c2d"),
            p("banana", "There's always money", "#fdfe0a", "#fed638", "#977b25", "#221a09"),
            p("gelc", "Golden Elephant Curry", "#ff9c00", "#c27600", "#4f3000", "#000000"),
            p("sfh", "Sunflower Holidays", "#ffff55", "#ff5555", "#881400", "#000000"),
            p("tdoyc", "The death of Yung Columbus", "#b5ff32", "#ff2261", "#462917", "#1d1414"),
            p("marmx", "Metroid Aran remixed", "#aedf1e", "#047e60", "#b62558", "#2c1700"),
            p("spezi", "My Friend from Bavaria", "#feda1b", "#df7925", "#b60077", "#382977"),
            p("kditw", "Knee-Deep in the Wood", "#fffe6e", "#d5690f", "#3c3ca9", "#2c2410"),
            p("nc", "Nortorious Comandante", "#fcfe54", "#54fefc", "#04aaac", "#0402ac"),
            p("cmyk", "CMYKeystone", "#ffff00", "#0be8fd", "#fb00fa", "#373737"),
            p("cga1", "CGA Palette Crush 1", "#ffffff", "#55ffff", "#ff55ff", "#000000"),
            p("cga2", "CGA Palette Crush 2", "#ffffff", "#55ffff", "#ff5555", "#000000"),
            p("vb85", "Virtual Boy 1985", "#ff0000", "#db0000", "#520000", "#000000"),
            p("aqpp", "Audi Quattro Pikes Peak", "#ebeee7", "#868779", "#fa2b25", "#2a201e"),
            p("yirl", "Youth Ikarus reloaded", "#cef7f7", "#f78e50", "#9e0000", "#1e0000"),
        )
    }
}
