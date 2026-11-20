package debating.ground.app.room

import kotlin.math.abs
import kotlin.math.ceil

// 邀请链接的二维码（#151）：网页版 src/lib/qr.ts 的移植，同样只做字节模式、纠错等级 M、版本 1–40、按标准罚分挑掩码。
// FixturesTest 用网页版生成的标准答案（qr-cases.json）逐格比对，两边挑的版本和掩码也必须一样。

private val ECC_PER_BLOCK = intArrayOf(
    -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28,
    28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
)
private val BLOCKS = intArrayOf(
    -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25,
    26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
)
private const val ECL_BITS = 0

/** 二维码：size × size，modules[y][x] 为 true 是深色格。 */
class QrCode(val size: Int, val version: Int, val mask: Int, val modules: Array<BooleanArray>)

private fun rawModules(ver: Int): Int {
    var n = (16 * ver + 128) * ver + 64
    if (ver >= 2) {
        val align = ver / 7 + 2
        n -= (25 * align - 10) * align - 55
        if (ver >= 7) n -= 36
    }
    return n
}

private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]

private fun gfMul(x: Int, y: Int): Int {
    var z = 0
    for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11d)
        z = z xor (((y ushr i) and 1) * x)
    }
    return z
}

private fun rsDivisor(degree: Int): IntArray {
    val r = IntArray(degree)
    r[degree - 1] = 1
    var root = 1
    repeat(degree) {
        for (j in r.indices) {
            r[j] = gfMul(r[j], root)
            if (j + 1 < r.size) r[j] = r[j] xor r[j + 1]
        }
        root = gfMul(root, 0x02)
    }
    return r
}

private fun rsRemainder(data: List<Int>, divisor: IntArray): List<Int> {
    val r = ArrayDeque(List(divisor.size) { 0 })
    for (b in data) {
        val factor = b xor r.removeFirst()
        r.addLast(0)
        divisor.forEachIndexed { i, coef -> r[i] = r[i] xor gfMul(coef, factor) }
    }
    return r.toList()
}

private fun codewords(bytes: ByteArray, ver: Int): List<Int> {
    val bits = ArrayList<Int>()
    fun put(v: Int, len: Int) {
        for (i in len - 1 downTo 0) bits += (v ushr i) and 1
    }
    put(0b0100, 4)
    put(bytes.size, if (ver <= 9) 8 else 16)
    bytes.forEach { put(it.toInt() and 0xff, 8) }
    val cap = dataCodewords(ver) * 8
    put(0, minOf(4, cap - bits.size))
    put(0, (8 - bits.size % 8) % 8)
    var pad = 0xec
    while (bits.size < cap) {
        put(pad, 8)
        pad = pad xor 0xec xor 0x11
    }
    val data = (0 until bits.size / 8).map { i -> (0 until 8).fold(0) { a, k -> (a shl 1) or bits[i * 8 + k] } }

    val blocks = BLOCKS[ver]
    val ecc = ECC_PER_BLOCK[ver]
    val total = rawModules(ver) / 8
    val shortBlocks = blocks - total % blocks
    val shortLen = total / blocks
    val div = rsDivisor(ecc)
    val all = ArrayList<MutableList<Int>>()
    var k = 0
    for (i in 0 until blocks) {
        val n = shortLen - ecc + (if (i < shortBlocks) 0 else 1)
        val dat = data.subList(k, k + n)
        k += n
        val block = (dat + rsRemainder(dat, div)).toMutableList()
        // 短块在数据末尾补一个空位，交错时跳过
        if (i < shortBlocks) block.add(dat.size, -1)
        all += block
    }
    val out = ArrayList<Int>()
    for (i in all[0].indices) for (b in all) if (b[i] != -1) out += b[i]
    return out
}

private fun alignmentPositions(ver: Int): List<Int> {
    if (ver == 1) return emptyList()
    val n = ver / 7 + 2
    val step = if (ver == 32) 26 else ceil((ver * 4 + 4).toDouble() / (n * 2 - 2)).toInt() * 2
    val r = mutableListOf(6)
    var pos = ver * 4 + 10
    while (r.size < n) {
        r.add(1, pos)
        pos -= step
    }
    return r
}

private fun bchFormat(mask: Int): Int {
    val data = (ECL_BITS shl 3) or mask
    var rem = data
    repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
    return ((data shl 10) or rem) xor 0x5412
}

private fun bchVersion(ver: Int): Int {
    var rem = ver
    repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1f25) }
    return (ver shl 12) or rem
}

private val MASKS: List<(Int, Int) -> Boolean> = listOf(
    { x, y -> (x + y) % 2 == 0 },
    { _, y -> y % 2 == 0 },
    { x, _ -> x % 3 == 0 },
    { x, y -> (x + y) % 3 == 0 },
    { x, y -> (x / 3 + y / 2) % 2 == 0 },
    { x, y -> (x * y) % 2 + (x * y) % 3 == 0 },
    { x, y -> ((x * y) % 2 + (x * y) % 3) % 2 == 0 },
    { x, y -> ((x + y) % 2 + (x * y) % 3) % 2 == 0 },
)

private class Grid(val ver: Int) {
    val size = ver * 4 + 17
    val m = Array(size) { BooleanArray(size) }
    val fixed = Array(size) { BooleanArray(size) }

    fun set(x: Int, y: Int, dark: Boolean) {
        m[y][x] = dark
        fixed[y][x] = true
    }

    fun functionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val d = maxOf(abs(dx), abs(dy))
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
                }
            }
        }
        finder(3, 3)
        finder(size - 4, 3)
        finder(3, size - 4)
        val pos = alignmentPositions(ver)
        val last = pos.size - 1
        pos.forEachIndexed { i, a ->
            pos.forEachIndexed { j, b ->
                if (!((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0))) {
                    for (dy in -2..2) for (dx in -2..2) set(a + dx, b + dy, maxOf(abs(dx), abs(dy)) != 1)
                }
            }
        }
        format(0)
        if (ver >= 7) {
            val bits = bchVersion(ver)
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }
    }

    fun format(mask: Int) {
        val bits = bchFormat(mask)
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    // 从右下角开始，两列一组之字形往上往下
    fun place(data: List<Int>) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (v in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val up = (right + 1) and 2 == 0
                    val y = if (up) size - 1 - v else v
                    if (!fixed[y][x] && i < data.size * 8) {
                        m[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    fun applyMask(mask: Int) {
        val f = MASKS[mask]
        for (y in 0 until size) for (x in 0 until size) if (!fixed[y][x] && f(x, y)) m[y][x] = !m[y][x]
    }

    fun penalty(): Int {
        var score = 0
        val pat = booleanArrayOf(true, false, true, true, true, false, true)
        fun line(get: (Int) -> Boolean) {
            // 规则 1：同色连续 5 格以上；规则 3：1:1:3:1:1 的类定位图形，一边留 4 格浅色（越界算浅色）
            var run = 1
            for (i in 1..size) {
                if (i < size && get(i) == get(i - 1)) {
                    run++
                } else {
                    if (run >= 5) score += run - 2
                    run = 1
                }
            }
            fun at(i: Int) = i in 0 until size && get(i)
            for (i in 0..size - 7) {
                if (!pat.indices.all { k -> at(i + k) == pat[k] }) continue
                if ((1..4).all { k -> !at(i - k) }) score += 40
                if ((7..10).all { k -> !at(i + k) }) score += 40
            }
        }
        for (y in 0 until size) line { x -> m[y][x] }
        for (x in 0 until size) line { y -> m[y][x] }
        // 规则 2：2×2 同色块
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3
            }
        }
        // 规则 4：深色比例偏离 50%
        val dark = m.sumOf { row -> row.count { it } }
        val total = size * size
        val k = ceil(abs(dark * 20 - total * 10).toDouble() / total).toInt() - 1
        return score + k * 10
    }
}

/** 把文字编成二维码。[mask] 只给测试用。太长放不下时返回 null。 */
fun encodeQr(text: String, mask: Int? = null): QrCode? {
    val bytes = text.toByteArray(Charsets.UTF_8)
    var ver = 1
    while (ver <= 40 && 4 + (if (ver <= 9) 8 else 16) + bytes.size * 8 > dataCodewords(ver) * 8) ver++
    if (ver > 40) return null
    val data = codewords(bytes, ver)
    fun build(k: Int) = Grid(ver).apply {
        functionPatterns()
        place(data)
        applyMask(k)
        format(k)
    }
    val (best, chosen) = if (mask != null) {
        build(mask) to mask
    } else {
        (0 until 8).map { build(it) to it }.minBy { (g, _) -> g.penalty() }
    }
    return QrCode(best.size, ver, chosen, best.m)
}
