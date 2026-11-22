package com.fuguang.lueying

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle

/*
 * 全 App 的圆角都是连续曲率（iOS 那种超椭圆，第 558 版起，照 FlClash）：普通圆角在直边接圆弧处曲率突变，看着「硬」。
 * 同名同参数顶替 androidx 的 RoundedCornerShape，调用处不用改；文件里不要再 import androidx 的那个，否则会盖过这里。
 * 形状本身来自 Kyant0/Shapes（com.kyant.shapes，源码随附）。
 */

private val pill = Capsule()

fun RoundedCornerShape(size: Dp): Shape = RoundedRectangle(size)

/** 50 = 胶囊；其他百分比按短边算。 */
fun RoundedCornerShape(percent: Int): Shape = if (percent >= 50) pill else PercentCorner(percent)

fun RoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): Shape =
    UnevenRoundedRectangle(topStart, topEnd, bottomEnd, bottomStart)

@Immutable
private class PercentCorner(private val percent: Int) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { (size.minDimension * percent / 100f).toDp() }
        return RoundedRectangle(r).createOutline(size, layoutDirection, density)
    }
    override fun equals(other: Any?) = other is PercentCorner && other.percent == percent
    override fun hashCode() = percent
}
