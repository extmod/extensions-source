package eu.kanade.tachiyomi.extension.all.manhuarm.interceptors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.extension.all.manhuarm.Dialog
import eu.kanade.tachiyomi.extension.all.manhuarm.Language
import eu.kanade.tachiyomi.extension.all.manhuarm.Manhuarm.Companion.PAGE_REGEX
import keiyoushi.utils.parseAs
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

@RequiresApi(Build.VERSION_CODES.O)
class ComposedImageInterceptor(
    val language: Language,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (PAGE_REGEX.containsMatchIn(url).not()) {
            return chain.proceed(request)
        }

        val dialogues = request.url.fragment?.parseAs<List<Dialog>>()
            ?: emptyList()

        val imageRequest = request.newBuilder()
            .url(url)
            .build()

        val response = chain.proceed(imageRequest)

        if (response.isSuccessful.not()) {
            return response
        }

        val bitmap = BitmapFactory.decodeStream(response.body.byteStream())!!
            .copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(bitmap)

        dialogues.forEach { dialog ->
            val scaledDialog = dialog.scaled(bitmap.width)
            scaledDialog.scale = language.dialogBoxScale
            val textPaint = createTextPaint(selectFontFamily())
            val dialogBox = createDialogBox(scaledDialog, textPaint)
            val y = getYAxis(textPaint, scaledDialog, dialogBox)
            canvas.draw(textPaint, dialogBox, scaledDialog, scaledDialog.x, y)
        }

        val output = ByteArrayOutputStream()

        val ext = url.substringBefore("#")
            .substringAfterLast(".")
            .lowercase()
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "jpeg", "jpg" -> Bitmap.CompressFormat.JPEG
            else -> Bitmap.CompressFormat.WEBP
        }

        bitmap.compress(format, 100, output)

        val responseBody = output.toByteArray().toResponseBody(mediaType)

        return response.newBuilder()
            .body(responseBody)
            .build()
    }

    private fun createTextPaint(font: Typeface?): TextPaint {
        val defaultTextSize = language.fontSize.pt
        return TextPaint().apply {
            color = Color.BLACK
            textSize = defaultTextSize
            font?.let {
                typeface = it
            }
            isAntiAlias = true
        }
    }

    private fun selectFontFamily(): Typeface? {
        if (language.disableFontSettings) {
            return null
        }
        return loadFont("${language.fontName}.ttf")
    }

    private fun loadFont(fontName: String): Typeface? = try {
        this::class.java.classLoader!!
            .getResourceAsStream("assets/fonts/$fontName")
            .toTypeface(fontName)
    } catch (e: Exception) {
        null
    }

    private fun InputStream.toTypeface(fontName: String): Typeface? {
        val fontFile = File.createTempFile(fontName, fontName.substringAfter("."))
        this.copyTo(FileOutputStream(fontFile))
        return Typeface.createFromFile(fontFile)
    }

    private fun getYAxis(textPaint: TextPaint, dialog: Dialog, dialogBox: StaticLayout): Float {
        val fontHeight = textPaint.fontMetrics.let { it.bottom - it.top }
        val dialogBoxLineCount = dialog.height / fontHeight
        return when {
            dialogBox.lineCount < dialogBoxLineCount -> dialog.centerY - dialogBox.lineCount / 2f * fontHeight
            else -> dialog.y
        }
    }

    private fun createDialogBox(dialog: Dialog, textPaint: TextPaint): StaticLayout {
        var dialogBox = createBoxLayout(dialog, textPaint)

        while (dialogBox.height > dialog.height) {
            textPaint.textSize -= 0.5f
            dialogBox = createBoxLayout(dialog, textPaint)
        }

        textPaint.color = Color.BLACK
        textPaint.bgColor = Color.WHITE

        return dialogBox
    }

    private fun createBoxLayout(dialog: Dialog, textPaint: TextPaint): StaticLayout {
        val text = dialog.getTextBy(language)

        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, dialog.width.toInt()).apply {
            setAlignment(Layout.Alignment.ALIGN_CENTER)
            setIncludePad(language.disableFontSettings)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (language.disableWordBreak) {
                    setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                    setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    return@apply
                }
                setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
            }
        }.build()
    }

    private fun Canvas.draw(textPaint: TextPaint, layout: StaticLayout, dialog: Dialog, x: Float, y: Float) {
        save()
        translate(x, y)
        rotate(dialog.angle)
        drawTextOutline(textPaint, layout)
        drawText(textPaint, layout)
        restore()
    }

    private fun Canvas.drawText(textPaint: TextPaint, layout: StaticLayout) {
        textPaint.style = Paint.Style.FILL
        layout.draw(this)
    }

    private fun Canvas.drawTextOutline(textPaint: TextPaint, layout: StaticLayout) {
        val foregroundColor = textPaint.color
        val style = textPaint.style

        textPaint.strokeWidth = 5F
        textPaint.color = textPaint.bgColor
        textPaint.style = Paint.Style.FILL_AND_STROKE

        layout.draw(this)

        textPaint.color = foregroundColor
        textPaint.style = style
    }

    private val Int.pt: Float get() = this / SCALED_DENSITY

    companion object {
        const val SCALED_DENSITY = 0.75f
        val mediaType = "image/png".toMediaType()
    }
}
