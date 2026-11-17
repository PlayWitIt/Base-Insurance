package com.greysworkshop.baseinsurance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Areas made of shapes, for other mods (Groundskeeper): rectangles, circles, ellipses, polygons and brush strokes,
 * each adding columns or cutting them out, applied in order. The same JSON and the same sums as Groundskeeper's
 * Shapes, so what the map shows is exactly what gets worked: column centres (x + 0.5, z + 0.5), one row at a time.
 */
final class AreaShapes {
	static final Gson GSON = new GsonBuilder().create();
	/** The most chunks an area may cover (Groundskeeper's limit): 16,000 × 16,000 blocks. */
	static final long MAX_CHUNKS = 1_000_000;

	static final class Shape {
		String op = "add";
		String type = "rect";
		int x1, z1, x2, z2;
		int cx, cz, rx, rz;
		int r;
		int[] points = new int[0];

		boolean cut() {
			return "cut".equals(op);
		}

		static Shape rect(int x1, int z1, int x2, int z2) {
			Shape s = new Shape();
			s.type = "rect";
			s.x1 = Math.min(x1, x2);
			s.z1 = Math.min(z1, z2);
			s.x2 = Math.max(x1, x2);
			s.z2 = Math.max(z1, z2);
			return s;
		}

		static Shape ellipse(int cx, int cz, int rx, int rz) {
			Shape s = new Shape();
			s.type = "ellipse";
			s.cx = cx;
			s.cz = cz;
			s.rx = Math.max(1, rx);
			s.rz = Math.max(1, rz);
			return s;
		}

		static Shape polygon(int[] points) {
			Shape s = new Shape();
			s.type = "polygon";
			s.points = points;
			return s;
		}

		static Shape brush(int r, int[] points) {
			Shape s = new Shape();
			s.type = "brush";
			s.r = Math.max(1, r);
			s.points = points;
			return s;
		}

		Shape op(boolean cut) {
			op = cut ? "cut" : "add";
			return this;
		}

		int[] bounds() {
			switch (type) {
				case "rect":
					return new int[] {x1, z1, x2, z2};
				case "ellipse":
					return new int[] {cx - rx, cz - rz, cx + rx, cz + rz};
				case "polygon":
				case "brush": {
					if (points == null || points.length < 2) return null;
					int grow = "brush".equals(type) ? r : 0;
					int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
					for (int i = 0; i + 1 < points.length; i += 2) {
						minX = Math.min(minX, points[i]);
						maxX = Math.max(maxX, points[i]);
						minZ = Math.min(minZ, points[i + 1]);
						maxZ = Math.max(maxZ, points[i + 1]);
					}
					return new int[] {minX - grow, minZ - grow, maxX + grow, maxZ + grow};
				}
				default:
					return null;
			}
		}

		boolean valid() {
			if (bounds() == null) return false;
			if ("polygon".equals(type)) return points.length >= 6;
			return true;
		}
	}

	static final class Data {
		int version = 1;
		List<Shape> shapes = new ArrayList<>();
	}

	static List<Shape> fromJson(String json) {
		List<Shape> list = new ArrayList<>();
		if (json == null || json.isBlank()) return list;
		try {
			Data d = GSON.fromJson(json, Data.class);
			if (d != null && d.shapes != null) for (Shape s : d.shapes) if (s != null && s.valid()) list.add(s);
		} catch (JsonParseException | IllegalStateException e) {
			BaseInsuranceClient.LOGGER.warn("Couldn't read an area's shapes: {}", e.toString());
		}
		return list;
	}

	static String toJson(List<Shape> shapes) {
		Data d = new Data();
		d.shapes = shapes;
		return GSON.toJson(d);
	}

	/** {minX, minZ, maxX, maxZ} of everything added, or null. */
	static int[] bounds(List<Shape> shapes) {
		int[] b = null;
		for (Shape s : shapes) {
			if (s.cut()) continue;
			int[] sb = s.bounds();
			if (sb == null) continue;
			if (b == null) b = sb.clone();
			else {
				b[0] = Math.min(b[0], sb[0]);
				b[1] = Math.min(b[1], sb[1]);
				b[2] = Math.max(b[2], sb[2]);
				b[3] = Math.max(b[3], sb[3]);
			}
		}
		return b;
	}

	/** Why it's too big, or null. */
	static String tooBig(List<Shape> shapes) {
		long chunks = 0;
		for (Shape s : shapes) {
			if (s.cut()) continue;
			int[] b = s.bounds();
			chunks += (long) ((b[2] >> 4) - (b[0] >> 4) + 1) * ((b[3] >> 4) - (b[1] >> 4) + 1);
		}
		return chunks > MAX_CHUNKS ? String.format(java.util.Locale.ROOT, "Too big: about %,d chunks, the most is %,d (16,000 × 16,000 blocks)", chunks, MAX_CHUNKS) : null;
	}

	/**
	 * The columns of row {@code z} that are in the area, as sorted, separate [from, to] runs (inclusive), with every
	 * shape applied in order. Groundskeeper's mask gets exactly these.
	 */
	static List<int[]> row(List<Shape> shapes, int z) {
		List<int[]> in = new ArrayList<>();
		List<double[]> runs = new ArrayList<>();
		for (Shape s : shapes) {
			int[] b = s.bounds();
			if (b == null || z < b[1] || z > b[3]) continue;
			runs.clear();
			spans(s, z + 0.5, runs);
			for (double[] run : runs) {
				int from = (int) Math.ceil(run[0] - 0.5 - 1e-9), to = (int) Math.floor(run[1] - 0.5 + 1e-9);
				if (from > to) continue;
				in = s.cut() ? subtract(in, from, to) : add(in, from, to);
			}
		}
		return in;
	}

	/** How many columns the area has. */
	static long columns(List<Shape> shapes) {
		int[] b = bounds(shapes);
		if (b == null) return 0;
		long n = 0;
		for (int z = b[1]; z <= b[3]; z++) for (int[] r : row(shapes, z)) n += r[1] - r[0] + 1;
		return n;
	}

	private static List<int[]> add(List<int[]> in, int from, int to) {
		List<int[]> out = new ArrayList<>();
		int a = from, b = to;
		boolean placed = false;
		for (int[] r : in) {
			if (r[1] < a - 1) out.add(r);
			else if (r[0] > b + 1) {
				if (!placed) {
					out.add(new int[] {a, b});
					placed = true;
				}
				out.add(r);
			} else {
				a = Math.min(a, r[0]);
				b = Math.max(b, r[1]);
			}
		}
		if (!placed) out.add(new int[] {a, b});
		return out;
	}

	private static List<int[]> subtract(List<int[]> in, int from, int to) {
		List<int[]> out = new ArrayList<>();
		for (int[] r : in) {
			if (r[1] < from || r[0] > to) {
				out.add(r);
				continue;
			}
			if (r[0] < from) out.add(new int[] {r[0], from - 1});
			if (r[1] > to) out.add(new int[] {to + 1, r[1]});
		}
		return out;
	}

	static void spans(Shape s, double zc, List<double[]> out) {
		switch (s.type) {
			case "rect" -> {
				if (zc >= s.z1 && zc <= s.z2 + 1) out.add(new double[] {s.x1 + 0.5, s.x2 + 0.5});
			}
			case "ellipse" -> ellipseSpan(s.cx, s.cz, s.rx, s.rz, zc, out);
			case "brush" -> {
				List<double[]> dabs = new ArrayList<>();
				for (int i = 0; i + 1 < s.points.length; i += 2) ellipseSpan(s.points[i], s.points[i + 1], s.r, s.r, zc, dabs);
				union(dabs, out);
			}
			case "polygon" -> polygonSpans(s.points, zc, out);
			default -> {}
		}
	}

	private static void ellipseSpan(int cx, int cz, int rx, int rz, double zc, List<double[]> out) {
		double dz = zc - (cz + 0.5);
		double kz = (double) rz * rz + rz, kx = (double) rx * rx + rx;
		double left = 1 - dz * dz / kz;
		if (left < 0) return;
		double w = Math.sqrt(kx * left);
		out.add(new double[] {cx + 0.5 - w, cx + 0.5 + w});
	}

	private static void polygonSpans(int[] pts, double zc, List<double[]> out) {
		int n = pts.length / 2;
		if (n < 3) return;
		List<Double> xs = new ArrayList<>();
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = pts[2 * i] + 0.5, zi = pts[2 * i + 1] + 0.5, xj = pts[2 * j] + 0.5, zj = pts[2 * j + 1] + 0.5;
			if ((zi > zc) != (zj > zc)) xs.add(xi + (zc - zi) * (xj - xi) / (zj - zi));
		}
		xs.sort(Double::compare);
		for (int k = 0; k + 1 < xs.size(); k += 2) out.add(new double[] {xs.get(k), xs.get(k + 1)});
	}

	static void union(List<double[]> in, List<double[]> out) {
		if (in.isEmpty()) return;
		in.sort((a, b) -> Double.compare(a[0], b[0]));
		double[] cur = in.get(0).clone();
		for (int i = 1; i < in.size(); i++) {
			double[] r = in.get(i);
			if (r[0] <= cur[1] + 1) cur[1] = Math.max(cur[1], r[1]);
			else {
				out.add(cur);
				cur = r.clone();
			}
		}
		out.add(cur);
	}

	/** Adds brush dabs from the last one to (x, z), at most half a radius apart, so the stroke has no gaps. */
	static void stroke(List<Integer> points, int r, int x, int z) {
		int n = points.size();
		if (n < 2) {
			points.add(x);
			points.add(z);
			return;
		}
		int px = points.get(n - 2), pz = points.get(n - 1);
		double d = Math.hypot(x - px, z - pz);
		double step = Math.max(1, r / 2.0);
		if (d < step) return;
		int steps = (int) Math.ceil(d / step);
		for (int i = 1; i <= steps; i++) {
			points.add((int) Math.round(px + (x - px) * i / (double) steps));
			points.add((int) Math.round(pz + (z - pz) * i / (double) steps));
		}
	}
}
