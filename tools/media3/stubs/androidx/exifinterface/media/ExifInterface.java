package androidx.exifinterface.media;
/* minimal stand-in on top of the platform class (API 24+) */
public class ExifInterface {
  public static final String TAG_ORIENTATION = "Orientation";
  public static final int ORIENTATION_UNDEFINED = 0, ORIENTATION_NORMAL = 1, ORIENTATION_FLIP_HORIZONTAL = 2, ORIENTATION_ROTATE_180 = 3, ORIENTATION_FLIP_VERTICAL = 4, ORIENTATION_TRANSPOSE = 5, ORIENTATION_ROTATE_90 = 6, ORIENTATION_TRANSVERSE = 7, ORIENTATION_ROTATE_270 = 8;
  private final android.media.ExifInterface x;
  public ExifInterface(java.io.InputStream in) throws java.io.IOException { x = new android.media.ExifInterface(in); }
  public int getAttributeInt(String tag, int def) { return x.getAttributeInt(tag, def); }
  public int getRotationDegrees() { switch (getAttributeInt(TAG_ORIENTATION, ORIENTATION_NORMAL)) { case ORIENTATION_ROTATE_90: case ORIENTATION_TRANSVERSE: return 90; case ORIENTATION_ROTATE_180: case ORIENTATION_FLIP_VERTICAL: return 180; case ORIENTATION_ROTATE_270: case ORIENTATION_TRANSPOSE: return 270; default: return 0; } }
}
