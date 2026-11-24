package com.vyxac;

/** Small fixed-size rolling buffer of doubles (newest overwrites oldest). */
public final class Ring {
    private final double[] a;
    private int n, i;

    public Ring(int size) {
        a = new double[size];
    }

    public void add(double v) {
        a[i] = v;
        i = (i + 1) % a.length;
        if (n < a.length) n++;
    }

    public int size() { return n; }
    public int capacity() { return a.length; }
    public boolean full() { return n == a.length; }

    /** k-th newest value (0 = newest). */
    public double get(int k) {
        return a[((i - 1 - k) % a.length + a.length) % a.length];
    }

    public double mean() {
        if (n == 0) return 0;
        double s = 0;
        for (int k = 0; k < n; k++) s += get(k);
        return s / n;
    }

    public double std() {
        if (n < 2) return 0;
        double m = mean(), s = 0;
        for (int k = 0; k < n; k++) {
            double d = get(k) - m;
            s += d * d;
        }
        return Math.sqrt(s / (n - 1));
    }

    public int countBelow(double x) {
        int c = 0;
        for (int k = 0; k < n; k++) if (get(k) < x) c++;
        return c;
    }

    public int countAbove(double x) {
        int c = 0;
        for (int k = 0; k < n; k++) if (get(k) > x) c++;
        return c;
    }

    public void clear() {
        n = 0;
        i = 0;
    }
}
