package sun.misc;
public final class Unsafe {
  public static Unsafe getUnsafe() { return null; }
  public int arrayBaseOffset(Class<?> c) { return 0; } public int arrayIndexScale(Class<?> c) { return 0; }
  public long getLong(Object o, long off) { return 0; } public int getInt(Object o, long off) { return 0; }
  public void putLong(Object o, long off, long v) {} public void putInt(Object o, long off, int v) {}
  public long objectFieldOffset(java.lang.reflect.Field f) { return 0; }
  public boolean compareAndSwapObject(Object o, long off, Object e, Object x) { return false; }
  public boolean compareAndSwapInt(Object o, long off, int e, int x) { return false; }
  public boolean compareAndSwapLong(Object o, long off, long e, long x) { return false; }
  public void putObject(Object o, long off, Object x) {} public Object getObject(Object o, long off) { return null; }
  public void putOrderedObject(Object o, long off, Object x) {} public Object getObjectVolatile(Object o, long off) { return null; }
  public void putObjectVolatile(Object o, long off, Object x) {}
}
