package com.tingjian.assistant;

public final class ImageWatchPolicyTest {
    private static int assertions;

    public static void main(String[] args) {
        imageClassesAndLabels();
        prominenceThresholds();
        differenceHashAndDistance();
        settleAndCooldownTiming();
        System.out.println("Image watch policy: " + assertions + " assertions passed.");
    }

    private static void imageClassesAndLabels() {
        check(ImageWatchPolicy.isImageClass("android.widget.ImageView"), "framework ImageView");
        check(ImageWatchPolicy.isImageClass("androidx.appcompat.widget.AppCompatImageView"), "AppCompat ImageView");
        check(ImageWatchPolicy.isImageClass("android.widget.Image"), "WebView image");
        check(ImageWatchPolicy.isImageClass("com.facebook.drawee.view.SimpleDraweeView"), "Fresco view");
        check(!ImageWatchPolicy.isImageClass("android.widget.ImageButton"), "icon buttons are not pictures");
        check(!ImageWatchPolicy.isImageClass("android.widget.TextView"), "text is not a picture");
        check(!ImageWatchPolicy.isImageClass(null), "missing class name");
        check(ImageWatchPolicy.isGenericLabel(null) && ImageWatchPolicy.isGenericLabel("  "), "blank labels are generic");
        check(ImageWatchPolicy.isGenericLabel("图片") && ImageWatchPolicy.isGenericLabel(" Image ") && ImageWatchPolicy.isGenericLabel("照片。"), "placeholder labels are generic");
        check(!ImageWatchPolicy.isGenericLabel("一只在草地上奔跑的金毛犬"), "real alt text is kept for the screen reader");
        check(!ImageWatchPolicy.isGenericLabel("图片：海边日落"), "descriptive label with prefix is not generic");
    }

    private static void prominenceThresholds() {
        check(ImageWatchPolicy.isProminent(1080, 720, 1080, 2400), "wide photo in a feed");
        check(ImageWatchPolicy.isProminent(600, 600, 1080, 2400), "square picture");
        check(!ImageWatchPolicy.isProminent(160, 160, 1080, 2400), "thumbnail ignored");
        check(!ImageWatchPolicy.isProminent(1080, 200, 1080, 2400), "thin banner ignored");
        check(!ImageWatchPolicy.isProminent(0, 600, 1080, 2400), "empty bounds ignored");
        check(!ImageWatchPolicy.isProminent(600, 600, 0, 0), "unknown screen size ignored");
    }

    private static void differenceHashAndDistance() {
        int[] gradient = new int[ImageWatchPolicy.HASH_WIDTH * ImageWatchPolicy.HASH_HEIGHT];
        int[] reverse = new int[gradient.length];
        for (int index = 0; index < gradient.length; index++) {
            gradient[index] = (index % ImageWatchPolicy.HASH_WIDTH) * 28;
            reverse[index] = 255 - gradient[index];
        }
        long bright = ImageWatchPolicy.differenceHash(gradient);
        long dark = ImageWatchPolicy.differenceHash(reverse);
        check(bright == -1L, "rising gradient sets every bit");
        check(dark == 0L, "falling gradient clears every bit");
        check(ImageWatchPolicy.hammingDistance(bright, dark) == 64, "opposite hashes are maximally distant");
        int[] noisy = gradient.clone();
        noisy[4] = 0; noisy[13] = 0;
        long similar = ImageWatchPolicy.differenceHash(noisy);
        check(ImageWatchPolicy.hammingDistance(bright, similar) <= ImageWatchPolicy.SAME_IMAGE_DISTANCE, "small pixel noise stays within tolerance");
        ImageWatchPolicy policy = new ImageWatchPolicy();
        check(policy.isNewImage(bright), "first picture is new");
        policy.onDescribed(0, bright);
        check(!policy.isNewImage(similar), "near-identical picture is not described again");
        check(policy.isNewImage(dark), "different picture is new");
        boolean rejected = false;
        try { ImageWatchPolicy.differenceHash(new int[3]); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "wrong grid size is rejected");
    }

    private static void settleAndCooldownTiming() {
        ImageWatchPolicy policy = new ImageWatchPolicy();
        check(!policy.isPending() && policy.remaining(0) == 0, "idle policy has nothing pending");
        policy.onScreenChanged(1000);
        check(policy.isPending() && !policy.shouldEvaluate(1500), "screen must settle first");
        check(policy.remaining(1500) == ImageWatchPolicy.SETTLE_MS - 500, "remaining counts down to settle");
        policy.onScreenChanged(2000);
        check(!policy.shouldEvaluate(2200 + ImageWatchPolicy.SETTLE_MS - 1000), "new change restarts the settle timer");
        check(policy.shouldEvaluate(2000 + ImageWatchPolicy.SETTLE_MS), "due once quiet for the settle time");
        policy.consume();
        check(!policy.isPending(), "evaluation consumes the pending change");
        for (long time = 10_000; time <= 12_800; time += 400) policy.onScreenChanged(time);
        check(!policy.shouldEvaluate(12_999), "continuous changes wait up to the cap");
        check(policy.shouldEvaluate(10_000 + ImageWatchPolicy.MAX_WAIT_MS), "continuous changes are inspected after the cap");
        policy.consume();
        policy.onDescribed(20_000, 42L);
        policy.onScreenChanged(20_100);
        long due = 20_000 + ImageWatchPolicy.COOLDOWN_MS;
        check(!policy.shouldEvaluate(due - 1) && policy.remaining(due - 1) == 1, "cooldown delays the next inspection");
        check(policy.shouldEvaluate(due), "inspection resumes after cooldown");
        policy.reset();
        check(!policy.isPending() && policy.isNewImage(42L), "reset forgets pending change and last picture");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
