package androidx.collection;
public final class CircularIntArray {
  private int[] e = new int[8]; private int head, tail, mask = 7;
  public CircularIntArray() {}
  public CircularIntArray(int min) { int c = 8; while (c < min) c <<= 1; e = new int[c]; mask = c - 1; }
  private void grow() { int n = e.length, r = n - head; int[] a = new int[n << 1]; System.arraycopy(e, head, a, 0, r); System.arraycopy(e, 0, a, r, head); e = a; head = 0; tail = n; mask = a.length - 1; }
  public void addFirst(int v) { head = (head - 1) & mask; e[head] = v; if (head == tail) grow(); }
  public void addLast(int v) { e[tail] = v; tail = (tail + 1) & mask; if (tail == head) grow(); }
  public int popFirst() { if (head == tail) throw new ArrayIndexOutOfBoundsException(); int v = e[head]; head = (head + 1) & mask; return v; }
  public int popLast() { if (head == tail) throw new ArrayIndexOutOfBoundsException(); tail = (tail - 1) & mask; return e[tail]; }
  public void clear() { tail = head; }
  public int getFirst() { if (head == tail) throw new ArrayIndexOutOfBoundsException(); return e[head]; }
  public int getLast() { if (head == tail) throw new ArrayIndexOutOfBoundsException(); return e[(tail - 1) & mask]; }
  public int get(int i) { if (i < 0 || i >= size()) throw new ArrayIndexOutOfBoundsException(); return e[(head + i) & mask]; }
  public int size() { return (tail - head) & mask; }
  public boolean isEmpty() { return head == tail; }
  public void removeFromStart(int n) { if (n <= 0) return; if (n > size()) throw new ArrayIndexOutOfBoundsException(); head = (head + n) & mask; }
  public void removeFromEnd(int n) { if (n <= 0) return; if (n > size()) throw new ArrayIndexOutOfBoundsException(); tail = (tail - n) & mask; }
}
