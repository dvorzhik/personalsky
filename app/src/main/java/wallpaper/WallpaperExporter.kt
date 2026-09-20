package com.personalsky.app.wallpaper

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.personalsky.app.data.local.CheckInType
import com.personalsky.app.data.local.StoredGoal
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WallpaperExporter {
    private const val WIDTH = 1080
    private const val HEIGHT = 2400
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFF4F7FF.toInt()
    private const val LAVENDER = 0xFFB9C3D8.toInt()
    private const val MIST = 0xFF8993A8.toInt()
    private const val GOLD = 0xFFFFDE8A.toInt()
    private const val VIOLET = 0xFF8A7BFF.toInt()

    suspend fun exportCompletedGoal(context: Context, goal: StoredGoal): String = withContext(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            drawWallpaper(bitmap, goal)
            saveBitmap(context, bitmap, fileNameFor(goal))
        } catch (_: Exception) {
            "Не удалось сохранить обои. Попробуйте ещё раз."
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawWallpaper(bitmap: Bitmap, goal: StoredGoal) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        drawBackgroundStars(canvas, paint)
        drawNebulaGlow(canvas, paint)

        val figure = figureFor(goal.durationDays)
        drawFigureSilhouette(canvas, paint, figure)
        drawProgressConstellation(canvas, paint, goal, figure)
        drawTypography(canvas, paint, goal, figure.name)
    }

    private fun drawBackgroundStars(canvas: Canvas, paint: Paint) {
        val stars = listOf(
            Triple(0.10f, 0.10f, 1.0f), Triple(0.83f, 0.08f, 1.4f),
            Triple(0.22f, 0.18f, 0.8f), Triple(0.72f, 0.16f, 1.1f),
            Triple(0.05f, 0.31f, 0.9f), Triple(0.92f, 0.29f, 1.2f),
            Triple(0.16f, 0.42f, 1.0f), Triple(0.85f, 0.45f, 0.8f),
            Triple(0.09f, 0.58f, 1.3f), Triple(0.94f, 0.63f, 0.9f),
            Triple(0.21f, 0.74f, 0.8f), Triple(0.79f, 0.77f, 1.2f),
            Triple(0.07f, 0.86f, 1.1f), Triple(0.89f, 0.90f, 0.8f)
        )
        paint.color = WHITE
        stars.forEach { (x, y, radius) ->
            paint.alpha = 120
            canvas.drawCircle(x * WIDTH, y * HEIGHT, radius * 2.0f, paint)
        }
    }

    private fun drawNebulaGlow(canvas: Canvas, paint: Paint) {
        paint.color = LAVENDER
        paint.alpha = 12
        canvas.drawCircle(WIDTH * 0.78f, HEIGHT * 0.28f, 260f, paint)
        paint.alpha = 18
        canvas.drawCircle(WIDTH * 0.24f, HEIGHT * 0.66f, 180f, paint)
    }

    private fun drawFigureSilhouette(canvas: Canvas, paint: Paint, figure: WallpaperFigure) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 4f
        paint.color = LAVENDER
        paint.alpha = 42

        figure.outlinePaths.forEach { normalizedPath ->
            val path = Path()
            normalizedPath.forEachIndexed { index, point ->
                val x = point.x * WIDTH
                val y = point.y * HEIGHT
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        paint.style = Paint.Style.FILL
        figure.outlineStars.forEach { star ->
            paint.color = LAVENDER
            paint.alpha = 55
            canvas.drawCircle(star.x * WIDTH, star.y * HEIGHT, 5f, paint)
        }
    }

    private fun drawProgressConstellation(
        canvas: Canvas,
        paint: Paint,
        goal: StoredGoal,
        figure: WallpaperFigure
    ) {
        val points = figure.progressPoints
        val typesByDay = goal.checkIns.associate { it.dayIndex to it.type }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND
        figure.progressEdges.forEach { edge ->
            val first = points[edge.first]
            val second = points[edge.second]
            val firstDone = typesByDay[edge.first] != null
            val secondDone = typesByDay[edge.second] != null
            paint.color = if (firstDone && secondDone) LAVENDER else MIST
            paint.alpha = if (firstDone && secondDone) 145 else 44
            canvas.drawLine(
                first.x * WIDTH,
                first.y * HEIGHT,
                second.x * WIDTH,
                second.y * HEIGHT,
                paint
            )
        }

        paint.style = Paint.Style.FILL
        points.forEachIndexed { dayIndex, point ->
            when (typesByDay[dayIndex]) {
                CheckInType.BRIGHT -> drawBrightStar(canvas, paint, point)
                CheckInType.STANDARD -> drawStandardStar(canvas, paint, point)
                CheckInType.MISSED -> drawNebula(canvas, paint, point)
                null -> drawQuietStar(canvas, paint, point)
            }
        }
    }

    private fun drawQuietStar(canvas: Canvas, paint: Paint, point: WallpaperPoint) {
        paint.color = MIST
        paint.alpha = 74
        canvas.drawCircle(point.x * WIDTH, point.y * HEIGHT, 6f, paint)
    }

    private fun drawStandardStar(canvas: Canvas, paint: Paint, point: WallpaperPoint) {
        val x = point.x * WIDTH
        val y = point.y * HEIGHT
        paint.color = WHITE
        paint.alpha = 35
        canvas.drawCircle(x, y, 26f, paint)
        paint.alpha = 245
        canvas.drawCircle(x, y, 9f, paint)
    }

    private fun drawBrightStar(canvas: Canvas, paint: Paint, point: WallpaperPoint) {
        val x = point.x * WIDTH
        val y = point.y * HEIGHT
        paint.color = GOLD
        paint.alpha = 20
        canvas.drawCircle(x, y, 66f, paint)
        paint.alpha = 48
        canvas.drawCircle(x, y, 39f, paint)
        paint.alpha = 255
        canvas.drawCircle(x, y, 12f, paint)
        paint.color = WHITE
        canvas.drawCircle(x, y, 6f, paint)

        paint.color = GOLD
        paint.alpha = 130
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(x - 34f, y, x + 34f, y, paint)
        canvas.drawLine(x, y - 34f, x, y + 34f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawNebula(canvas: Canvas, paint: Paint, point: WallpaperPoint) {
        val x = point.x * WIDTH
        val y = point.y * HEIGHT
        paint.color = VIOLET
        paint.alpha = 16
        canvas.drawCircle(x, y, 42f, paint)
        paint.alpha = 34
        canvas.drawCircle(x, y, 20f, paint)
        paint.alpha = 95
        canvas.drawCircle(x, y, 5f, paint)
    }

    private fun drawTypography(canvas: Canvas, paint: Paint, goal: StoredGoal, constellationName: String) {
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.color = LAVENDER
        paint.alpha = 230
        paint.textSize = 24f
        paint.letterSpacing = 0.16f
        canvas.drawText("PERSONAL SKY", WIDTH / 2f, 170f, paint)

        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.color = WHITE
        paint.alpha = 245
        paint.textSize = 58f
        paint.letterSpacing = 0f
        drawCenteredMultiline(canvas, paint, goal.title, WIDTH / 2f, 2040f, 72f)

        paint.color = LAVENDER
        paint.alpha = 225
        paint.textSize = 28f
        canvas.drawText(constellationName, WIDTH / 2f, 2160f, paint)

        paint.color = MIST
        paint.alpha = 205
        paint.textSize = 26f
        canvas.drawText("Этот путь остался с вами", WIDTH / 2f, 2260f, paint)
    }

    private fun drawCenteredMultiline(
        canvas: Canvas,
        paint: Paint,
        text: String,
        centerX: Float,
        baselineY: Float,
        lineHeight: Float
    ) {
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var line = ""
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= WIDTH * 0.82f) {
                line = candidate
            } else {
                if (line.isNotEmpty()) lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line

        val startY = baselineY - ((lines.size - 1) * lineHeight / 2f)
        lines.forEachIndexed { index, lineText ->
            canvas.drawText(lineText, centerX, startY + index * lineHeight, paint)
        }
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap, displayName: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Personal Sky")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "Не удалось создать файл обоев."
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                } ?: return "Не удалось записать файл обоев."
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Обои сохранены в галерею."
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val directory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "Personal Sky"
            ).apply { mkdirs() }
            val output = File(directory, displayName)
            FileOutputStream(output).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(output.absolutePath),
                arrayOf("image/png"),
                null
            )
            "Обои сохранены в папку изображений."
        }
    }

    private fun fileNameFor(goal: StoredGoal): String {
        val safeTitle = goal.title
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            .take(32)
            .ifBlank { "constellation" }
        return "PersonalSky_${safeTitle}_${System.currentTimeMillis()}.png"
    }

    private data class WallpaperPoint(val x: Float, val y: Float)
    private data class WallpaperEdge(val first: Int, val second: Int)

    private data class WallpaperFigure(
        val name: String,
        val progressPoints: List<WallpaperPoint>,
        val progressEdges: List<WallpaperEdge>,
        val outlinePaths: List<List<WallpaperPoint>>,
        val outlineStars: List<WallpaperPoint>
    )

    private fun figureFor(days: Int): WallpaperFigure = when (days) {
        5 -> WallpaperFigure(
            name = "Кассиопея",
            progressPoints = listOf(
                WallpaperPoint(0.12f, 0.55f), WallpaperPoint(0.30f, 0.42f),
                WallpaperPoint(0.50f, 0.58f), WallpaperPoint(0.70f, 0.40f),
                WallpaperPoint(0.88f, 0.54f)
            ),
            progressEdges = listOf(
                WallpaperEdge(0, 1), WallpaperEdge(1, 2),
                WallpaperEdge(2, 3), WallpaperEdge(3, 4)
            ),
            outlinePaths = listOf(
                listOf(
                    WallpaperPoint(0.08f, 0.36f), WallpaperPoint(0.22f, 0.26f),
                    WallpaperPoint(0.38f, 0.31f), WallpaperPoint(0.50f, 0.20f),
                    WallpaperPoint(0.64f, 0.31f), WallpaperPoint(0.80f, 0.25f),
                    WallpaperPoint(0.93f, 0.36f)
                ),
                listOf(
                    WallpaperPoint(0.18f, 0.70f), WallpaperPoint(0.36f, 0.79f),
                    WallpaperPoint(0.52f, 0.74f), WallpaperPoint(0.69f, 0.80f),
                    WallpaperPoint(0.84f, 0.70f)
                )
            ),
            outlineStars = listOf(
                WallpaperPoint(0.08f, 0.36f), WallpaperPoint(0.22f, 0.26f),
                WallpaperPoint(0.50f, 0.20f), WallpaperPoint(0.80f, 0.25f),
                WallpaperPoint(0.93f, 0.36f), WallpaperPoint(0.18f, 0.70f),
                WallpaperPoint(0.84f, 0.70f)
            )
        )

        7 -> WallpaperFigure(
            name = "Большая Медведица",
            progressPoints = listOf(
                WallpaperPoint(0.24f, 0.59f), WallpaperPoint(0.35f, 0.51f),
                WallpaperPoint(0.46f, 0.44f), WallpaperPoint(0.57f, 0.36f),
                WallpaperPoint(0.70f, 0.29f), WallpaperPoint(0.83f, 0.41f),
                WallpaperPoint(0.70f, 0.60f)
            ),
            progressEdges = listOf(
                WallpaperEdge(0, 1), WallpaperEdge(1, 2), WallpaperEdge(2, 3),
                WallpaperEdge(3, 4), WallpaperEdge(4, 5), WallpaperEdge(5, 6),
                WallpaperEdge(6, 3)
            ),
            outlinePaths = listOf(
                listOf(
                    WallpaperPoint(0.16f, 0.64f), WallpaperPoint(0.10f, 0.53f),
                    WallpaperPoint(0.17f, 0.42f), WallpaperPoint(0.27f, 0.38f),
                    WallpaperPoint(0.34f, 0.29f), WallpaperPoint(0.46f, 0.22f),
                    WallpaperPoint(0.63f, 0.23f), WallpaperPoint(0.77f, 0.32f),
                    WallpaperPoint(0.88f, 0.45f), WallpaperPoint(0.84f, 0.62f),
                    WallpaperPoint(0.70f, 0.72f), WallpaperPoint(0.55f, 0.76f),
                    WallpaperPoint(0.39f, 0.73f), WallpaperPoint(0.24f, 0.71f),
                    WallpaperPoint(0.16f, 0.64f)
                ),
                listOf(
                    WallpaperPoint(0.22f, 0.67f), WallpaperPoint(0.12f, 0.78f),
                    WallpaperPoint(0.20f, 0.83f)
                ),
                listOf(
                    WallpaperPoint(0.61f, 0.72f), WallpaperPoint(0.72f, 0.86f),
                    WallpaperPoint(0.80f, 0.82f)
                )
            ),
            outlineStars = listOf(
                WallpaperPoint(0.10f, 0.53f), WallpaperPoint(0.34f, 0.29f),
                WallpaperPoint(0.63f, 0.23f), WallpaperPoint(0.88f, 0.45f),
                WallpaperPoint(0.70f, 0.72f), WallpaperPoint(0.24f, 0.71f),
                WallpaperPoint(0.12f, 0.78f), WallpaperPoint(0.72f, 0.86f)
            )
        )

        else -> WallpaperFigure(
            name = "Орион",
            progressPoints = listOf(
                WallpaperPoint(0.26f, 0.20f), WallpaperPoint(0.74f, 0.20f),
                WallpaperPoint(0.35f, 0.35f), WallpaperPoint(0.50f, 0.40f),
                WallpaperPoint(0.65f, 0.35f), WallpaperPoint(0.39f, 0.49f),
                WallpaperPoint(0.50f, 0.54f), WallpaperPoint(0.61f, 0.49f),
                WallpaperPoint(0.42f, 0.65f), WallpaperPoint(0.58f, 0.65f),
                WallpaperPoint(0.21f, 0.76f), WallpaperPoint(0.79f, 0.76f),
                WallpaperPoint(0.31f, 0.87f), WallpaperPoint(0.68f, 0.87f)
            ),
            progressEdges = listOf(
                WallpaperEdge(0, 2), WallpaperEdge(2, 3), WallpaperEdge(3, 4), WallpaperEdge(4, 1),
                WallpaperEdge(2, 5), WallpaperEdge(5, 6), WallpaperEdge(6, 7), WallpaperEdge(7, 4),
                WallpaperEdge(5, 8), WallpaperEdge(8, 10), WallpaperEdge(6, 9), WallpaperEdge(9, 11),
                WallpaperEdge(10, 12), WallpaperEdge(11, 13)
            ),
            outlinePaths = listOf(
                listOf(
                    WallpaperPoint(0.50f, 0.13f), WallpaperPoint(0.40f, 0.23f),
                    WallpaperPoint(0.34f, 0.36f), WallpaperPoint(0.28f, 0.51f),
                    WallpaperPoint(0.36f, 0.62f), WallpaperPoint(0.31f, 0.80f),
                    WallpaperPoint(0.40f, 0.88f), WallpaperPoint(0.50f, 0.93f),
                    WallpaperPoint(0.60f, 0.88f), WallpaperPoint(0.69f, 0.80f),
                    WallpaperPoint(0.64f, 0.62f), WallpaperPoint(0.72f, 0.51f),
                    WallpaperPoint(0.66f, 0.36f), WallpaperPoint(0.60f, 0.23f),
                    WallpaperPoint(0.50f, 0.13f)
                ),
                listOf(
                    WallpaperPoint(0.34f, 0.42f), WallpaperPoint(0.15f, 0.35f),
                    WallpaperPoint(0.10f, 0.48f), WallpaperPoint(0.28f, 0.55f)
                ),
                listOf(
                    WallpaperPoint(0.66f, 0.42f), WallpaperPoint(0.85f, 0.35f),
                    WallpaperPoint(0.90f, 0.48f), WallpaperPoint(0.72f, 0.55f)
                )
            ),
            outlineStars = listOf(
                WallpaperPoint(0.50f, 0.13f), WallpaperPoint(0.34f, 0.36f),
                WallpaperPoint(0.66f, 0.36f), WallpaperPoint(0.10f, 0.48f),
                WallpaperPoint(0.90f, 0.48f), WallpaperPoint(0.31f, 0.80f), WallpaperPoint(0.69f, 0.80f), WallpaperPoint(0.50f, 0.93f)
            )
        )
    }
}
