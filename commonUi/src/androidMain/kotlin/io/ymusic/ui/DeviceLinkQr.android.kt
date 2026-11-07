package io.ymusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
actual fun DeviceLinkQrCode(payload: String, modifier: Modifier) {
    val matrix = remember(payload) {
        QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            256,
            256,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }
    Canvas(modifier.background(Color.White)) {
        val cellW = size.width / matrix.width
        val cellH = size.height / matrix.height
        val overlap = 0.5f
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cellW, y * cellH),
                        size = Size(cellW + overlap, cellH + overlap),
                    )
                }
            }
        }
    }
}
