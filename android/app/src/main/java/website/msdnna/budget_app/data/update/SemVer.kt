package website.msdnna.budget_app.data.update

internal fun compareSemVer(a: String, b: String): Int {
    val pa = a.split('.').mapNotNull { it.toIntOrNull() }
    val pb = b.split('.').mapNotNull { it.toIntOrNull() }
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}
