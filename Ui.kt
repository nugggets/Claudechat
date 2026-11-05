fun Activity.statusBarSpacer(): View {
    val v = View(this)
    var h = 0
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    if (id > 0) h = resources.getDimensionPixelSize(id)
    if (h <= 0) h = dp(24)
    v.setBackgroundColor(Pal.bar)
    v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    return v
}
