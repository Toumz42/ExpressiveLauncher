package app.lawnchair.folder.widget

class FolderWidgetStyleEditor() {
    fun withColumns(s: FolderWidgetStyle, c: Int?): FolderWidgetStyle = s.copy(columns = c)

    fun withShowLabels(s: FolderWidgetStyle, v: Boolean): FolderWidgetStyle = s.copy(showLabels = v)

    fun withShowHeader(s: FolderWidgetStyle, v: Boolean): FolderWidgetStyle = s.copy(showHeader = v)

    fun withShowNameBelow(s: FolderWidgetStyle, v: Boolean): FolderWidgetStyle = s.copy(showNameBelow = v)

    fun withBackgroundColor(s: FolderWidgetStyle, c: Int?): FolderWidgetStyle =
        s.copy(backgroundColor = c)

    fun withBackgroundOpacity(s: FolderWidgetStyle, o: Float?): FolderWidgetStyle =
        s.copy(backgroundOpacity = o)

    fun withCornerRadius(s: FolderWidgetStyle, r: Float?): FolderWidgetStyle =
        s.copy(cornerRadiusPx = r)

    fun withIconScale(s: FolderWidgetStyle, k: Float?): FolderWidgetStyle =
        s.copy(iconScale = k)

    fun reset(): FolderWidgetStyle = FolderWidgetStyle()
}
