package com.dlab.myaipiceditor.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.dlab.myaipiceditor.R

object FontCollections {

    data class FontItem(
        val id: String,     // readable name
        val family: FontFamily
    )

    val fonts = listOf(
        FontItem("Archivo Black", FontFamily(Font(R.font.archivoblack_regular))),
        FontItem("Montserrat", FontFamily(Font(R.font.montserrat_regular))),
        FontItem("Open Sans", FontFamily(Font(R.font.opensans_regular))),
        FontItem("Roboto", FontFamily(Font(R.font.roboto_regular))),
        FontItem("Roboto Mono", FontFamily(Font(R.font.robotomono_regular))),
    )
}
