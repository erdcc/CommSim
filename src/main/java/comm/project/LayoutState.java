package comm.project;

/** Pencere yerlesimi: konum/boyut, bolme oranlari, trafik sutun genislikleri, secili alt sekme. */
public final class LayoutState {
    public int x = -1;
    public int y = -1;
    public int width;
    public int height;
    public boolean maximized;
    /** Sol liste genisligi (piksel). */
    public int listWidth;
    /** Ust/alt bolme orani (0..1). */
    public double rightRatio;
    /** Trafik tablosu / detay bolme orani (0..1). */
    public double trafficRatio;
    public int[] trafficColumns;
    public int bottomTab = -1;
}
