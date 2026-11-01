package com.lifeassistant.skill.travel.trip;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntBiFunction;

/**
 * 一天的路线顺序优化：锁定的、已预约的、吃饭、酒店、交通和没坐标的项位置和时间都不动，
 * 其余有坐标的景点在它们原来占的位置里换顺序，找路上时间最短、又不耽误后面固定安排的排法；
 * 换了顺序的景点按各自原来的游玩时长，从当天第一项的开始时间往后重新排时间。
 * 傍晚以后（17 点起）的景点多半是夜景、演出，只和同样在傍晚以后的位置互换；
 * 排下来会让固定安排晚开始的顺序不要（原来就晚的，不能比原来更晚）。
 */
final class RouteOptimizer {

    /** 景点数在这以内把所有顺序都试一遍（8! = 40320），再多就用两两交换逐步改进 */
    private static final int EXHAUSTIVE = 8;
    /** 从这个时间（分钟）开始算傍晚 */
    private static final int EVENING = 17 * 60;
    /** 每段车程之外留的停车、找入口时间；步行段留得少一些 */
    private static final int BUFFER = 10;
    private static final int WALK_BUFFER = 5;

    private RouteOptimizer() {
    }

    /** 两项之间的路程怎么算分：weight 按优化目标（分钟、公里等），minutes 是路上分钟数（排时间用）。 */
    interface Metric {
        double weight(Itinerary.Item a, Itinerary.Item b);

        int minutes(Itinerary.Item a, Itinerary.Item b);

        /** 新顺序至少要比原来省这么多（weight 的单位）才算值得换 */
        double minSaving();
    }

    /** 按直线距离估算的路上时间，没有高德数据时用 */
    static final Metric ESTIMATE = new Metric() {
        @Override
        public double weight(Itinerary.Item a, Itinerary.Item b) {
            return estimate(a, b);
        }

        @Override
        public int minutes(Itinerary.Item a, Itinerary.Item b) {
            return estimate(a, b);
        }

        @Override
        public double minSaving() {
            return 5;
        }
    };

    /** 优化结果：新顺序的各项（时间还没重排）、顺序有没有变。 */
    record Result(List<Itinerary.Item> items, boolean changed) {
    }

    /**
     * 按 metric 找代价最小的顺序。start、end 是当天出发和回去的住处（只用坐标），可以为空。
     */
    static Result optimize(List<Itinerary.Item> items, Set<String> booked, Itinerary.Item start, Itinerary.Item end,
                           Metric metric) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (movable(items.get(i), booked)) {
                slots.add(i);
            }
        }
        if (slots.size() < 2) {
            return new Result(items, false);
        }
        List<Itinerary.Item> movers = slots.stream().map(items::get).toList();
        int dayStart = startOf(items);
        double original = cost(items, start, end, metric);
        int allowedLate = lateness(items, booked, dayStart, metric);
        List<Itinerary.Item> best = items;
        double bestCost = original;
        if (movers.size() <= EXHAUSTIVE) {
            int[] perm = new int[movers.size()];
            for (int i = 0; i < perm.length; i++) {
                perm[i] = i;
            }
            do {
                if (!fits(slots, movers, perm, items)) {
                    continue;
                }
                List<Itinerary.Item> candidate = place(items, slots, movers, perm);
                double c = cost(candidate, start, end, metric);
                if (c < bestCost && lateness(candidate, booked, dayStart, metric) <= allowedLate) {
                    best = candidate;
                    bestCost = c;
                }
            } while (nextPermutation(perm));
        } else {
            int[] perm = new int[movers.size()];
            for (int i = 0; i < perm.length; i++) {
                perm[i] = i;
            }
            boolean improved = true;
            while (improved) {
                improved = false;
                for (int a = 0; a < perm.length; a++) {
                    for (int b = a + 1; b < perm.length; b++) {
                        swap(perm, a, b);
                        List<Itinerary.Item> candidate = place(items, slots, movers, perm);
                        double c = fits(slots, movers, perm, items) ? cost(candidate, start, end, metric)
                                : Double.MAX_VALUE;
                        if (c < bestCost && lateness(candidate, booked, dayStart, metric) <= allowedLate) {
                            best = candidate;
                            bestCost = c;
                            improved = true;
                        } else {
                            swap(perm, a, b);
                        }
                    }
                }
            }
        }
        boolean changed = bestCost <= original - metric.minSaving();
        return new Result(changed ? best : items, changed);
    }

    /** 傍晚以后的景点只能放到原来也在傍晚以后的位置，白天的同理。 */
    private static boolean fits(List<Integer> slots, List<Itinerary.Item> movers, int[] perm,
                                List<Itinerary.Item> items) {
        for (int k = 0; k < slots.size(); k++) {
            if (evening(items.get(slots.get(k))) != evening(movers.get(perm[k]))) {
                return false;
            }
        }
        return true;
    }

    private static boolean evening(Itinerary.Item item) {
        Integer start = minutes(item.start());
        return start != null && start >= EVENING;
    }

    /** 这一项能不能挪：有坐标的景点，没锁定、没预约。 */
    static boolean movable(Itinerary.Item item, Set<String> booked) {
        return "SPOT".equals(item.type()) && item.located() && !Boolean.TRUE.equals(item.locked())
                && !booked.contains(item.id());
    }

    /**
     * 按新顺序重排时间：固定的项保持原来的时间；能挪的项接在前一项后面，加上路上时间和 10 分钟余量，
     * 时长按它原来的时长。travel 给出两项之间的路上分钟数，dayStart 是当天原来第一项的开始时间（分钟）。
     */
    static List<Itinerary.Item> retime(List<Itinerary.Item> ordered, Set<String> booked,
                                       ToIntBiFunction<Itinerary.Item, Itinerary.Item> travel, int dayStart) {
        List<Itinerary.Item> out = new ArrayList<>();
        Integer cursor = null;
        Itinerary.Item prev = null;
        // 中间有一项"打车前往…"这类交通安排时，路上时间已经算在它里面了，不再重复加
        boolean bridged = false;
        for (Itinerary.Item item : ordered) {
            int arrive = cursor == null ? dayStart : cursor + (bridged ? 0 : gap(prev, item, travel));
            if (!movable(item, booked) && minutes(item.start()) != null) {
                out.add(item);
                cursor = Math.max(arrive, end(item));
            } else {
                int from = roundUp(arrive);
                int to = Math.min(from + duration(item), 23 * 60 + 59);
                out.add(new Itinerary.Item(item.id(), hhmm(from), hhmm(to), item.type(), item.name(), item.address(),
                        item.note(), item.cost(), item.url(), item.locked(), item.poiId(), item.lng(), item.lat(),
                        item.photo(), item.rating()));
                cursor = to;
            }
            if (item.located()) {
                prev = item;
                bridged = false;
            } else if ("TRANSPORT".equals(item.type())) {
                bridged = true;
            }
        }
        return out;
    }

    /** 估算的路上分钟数：1.2 公里内按步行（每小时 4.5 公里，绕路 1.3 倍），远的按城市里开车（每小时 30 公里，绕路 1.4 倍，加 5 分钟）。 */
    static int estimate(Itinerary.Item a, Itinerary.Item b) {
        double km = TripGeo.distanceKm(a.lat(), a.lng(), b.lat(), b.lng());
        return km <= 1.2 ? (int) Math.round(km * 1.3 / 4.5 * 60) : (int) Math.round(km * 1.4 / 30 * 60) + 5;
    }

    /** 各段路程的代价之和（含从住处出发、回住处）。 */
    private static double cost(List<Itinerary.Item> items, Itinerary.Item start, Itinerary.Item end, Metric metric) {
        double total = 0;
        Itinerary.Item first = null;
        Itinerary.Item last = null;
        for (Itinerary.Item item : items) {
            if (!item.located()) {
                continue;
            }
            if (last != null) {
                total += metric.weight(last, item);
            }
            if (first == null) {
                first = item;
            }
            last = item;
        }
        if (first != null && start != null) {
            total += metric.weight(start, first);
        }
        if (last != null && end != null) {
            total += metric.weight(last, end);
        }
        return total;
    }

    /** 按这个顺序排下来，固定的项一共要晚开始多少分钟。 */
    private static int lateness(List<Itinerary.Item> items, Set<String> booked, int dayStart, Metric metric) {
        return lateness(items, booked, dayStart, metric::minutes);
    }

    /** 按这个顺序、用 travel 给的路上时间排下来，固定的项一共要晚开始多少分钟。 */
    static int lateness(List<Itinerary.Item> items, Set<String> booked, int dayStart,
                        ToIntBiFunction<Itinerary.Item, Itinerary.Item> travel) {
        int late = 0;
        Integer cursor = null;
        Itinerary.Item prev = null;
        // 中间有一项"打车前往…"这类交通安排时，路上时间已经算在它里面了，不再重复加
        boolean bridged = false;
        for (Itinerary.Item item : items) {
            int arrive = cursor == null ? dayStart : cursor + (bridged ? 0 : gap(prev, item, travel));
            Integer fixedStart = movable(item, booked) ? null : minutes(item.start());
            if (fixedStart != null) {
                late += Math.max(0, arrive - fixedStart);
                cursor = Math.max(arrive, end(item));
            } else {
                cursor = roundUp(arrive) + duration(item);
            }
            if (item.located()) {
                prev = item;
                bridged = false;
            } else if ("TRANSPORT".equals(item.type())) {
                bridged = true;
            }
        }
        return late;
    }

    /** 从上一个有坐标的项过来要多久：两项都有坐标才算路程，加余量（15 分钟以内的短途少留一点）。 */
    private static int gap(Itinerary.Item prev, Itinerary.Item item,
                           ToIntBiFunction<Itinerary.Item, Itinerary.Item> travel) {
        if (prev == null || !item.located()) {
            return 0;
        }
        int minutes = travel.applyAsInt(prev, item);
        return minutes + (minutes < 15 ? WALK_BUFFER : BUFFER);
    }

    private static List<Itinerary.Item> place(List<Itinerary.Item> items, List<Integer> slots,
                                              List<Itinerary.Item> movers, int[] perm) {
        List<Itinerary.Item> out = new ArrayList<>(items);
        for (int k = 0; k < slots.size(); k++) {
            out.set(slots.get(k), movers.get(perm[k]));
        }
        return out;
    }

    /** 当天第一项的开始时间，没有就 9 点。 */
    static int startOf(List<Itinerary.Item> items) {
        Integer start = items.isEmpty() ? null : minutes(items.get(0).start());
        return start == null ? 9 * 60 : start;
    }

    /** 原来的时长（分钟），算不出来按 1 小时。 */
    static int duration(Itinerary.Item item) {
        Integer s = minutes(item.start());
        Integer e = minutes(item.end());
        return s == null || e == null || e <= s ? 60 : e - s;
    }

    private static int end(Itinerary.Item item) {
        Integer s = minutes(item.start());
        Integer e = minutes(item.end());
        return e != null && s != null && e > s ? e : (s == null ? 0 : s) + 60;
    }

    private static Integer minutes(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        try {
            LocalTime t = LocalTime.parse(hhmm.length() == 4 ? "0" + hhmm : hhmm);
            return t.getHour() * 60 + t.getMinute();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int roundUp(int minutes) {
        return (minutes + 4) / 5 * 5;
    }

    private static String hhmm(int minutes) {
        int m = Math.min(minutes, 23 * 60 + 59);
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    /** 字典序的下一个排列，已经是最后一个返回 false。 */
    private static boolean nextPermutation(int[] a) {
        int i = a.length - 2;
        while (i >= 0 && a[i] >= a[i + 1]) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int j = a.length - 1;
        while (a[j] <= a[i]) {
            j--;
        }
        swap(a, i, j);
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) {
            swap(a, l, r);
        }
        return true;
    }
}
