package com.adsa.hd

import android.graphics.ColorMatrix

/**
 * فلاتر الألوان. نفس المصفوفة تُستخدم في المعاينة الحية وعند حفظ الصورة،
 * فتظهر الصورة المحفوظة مطابقة لما رآه المستخدم.
 */
object Filters {

    class Filter(val label: String, val matrix: ColorMatrix?)

    private fun chain(vararg steps: ColorMatrix): ColorMatrix {
        val result = ColorMatrix()
        for (s in steps) result.postConcat(s)
        return result
    }

    private fun sat(v: Float) = ColorMatrix().apply { setSaturation(v) }

    private fun contrast(c: Float): ColorMatrix {
        val t = (1f - c) * 128f
        return ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun scale(r: Float, g: Float, b: Float, offset: Float = 0f) = ColorMatrix(
        floatArrayOf(
            r, 0f, 0f, 0f, offset,
            0f, g, 0f, 0f, offset,
            0f, 0f, b, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    )

    val all: List<Filter> = listOf(
        Filter("بدون", null),
        Filter("حيوي", chain(sat(1.35f), contrast(1.08f))),
        Filter("دافئ", chain(scale(1.08f, 1.0f, 0.90f, 4f), sat(1.05f))),
        Filter("بارد", chain(scale(0.92f, 1.0f, 1.08f), sat(1.05f))),
        Filter("أبيض وأسود", chain(sat(0f), contrast(1.15f))),
        Filter("كلاسيك", chain(sat(0.55f), scale(1.05f, 1.0f, 0.85f, 6f), contrast(0.95f))),
        Filter("باهت", chain(contrast(0.88f), scale(1f, 1f, 1f, 18f), sat(0.8f))),
        Filter("سينمائي", chain(contrast(1.1f), scale(1.04f, 0.98f, 1.06f, -6f), sat(1.1f)))
    )
}
