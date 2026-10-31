package com.google.geminiclone;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;

public final class ImageThumbnailCache {

    // 4MB in-memory LRU cache (safe even on 64MB/128MB Dalvik heaps from 2014 phones)
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ImageThumbnailCache() {}

    public static void loadInto(Context context, Uri uri, ImageView targetView, int maxWidthDp, int maxHeightDp) {
        if (uri == null || targetView == null || context == null) return;

        float density = context.getResources().getDisplayMetrics().density;
        int reqW = Math.max(32, (int) (maxWidthDp * density));
        int reqH = Math.max(32, (int) (maxHeightDp * density));
        String key = uri.toString() + "@" + reqW + "x" + reqH;

        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            targetView.setImageBitmap(cached);
            return;
        }

        try {
            ContentResolver cr = context.getContentResolver();

            // 1. Decode bounds only (zero pixel allocation)
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            try (InputStream is = cr.openInputStream(uri)) {
                if (is != null) {
                    BitmapFactory.decodeStream(is, null, boundsOpts);
                }
            }

            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                targetView.setImageURI(uri);
                return;
            }

            // 2. Calculate optimal power-of-two inSampleSize
            int inSampleSize = 1;
            int halfW = boundsOpts.outWidth / 2;
            int halfH = boundsOpts.outHeight / 2;
            while ((halfW / inSampleSize) >= reqW && (halfH / inSampleSize) >= reqH) {
                inSampleSize *= 2;
            }

            // 3. Decode subsampled bitmap
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = inSampleSize;
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap decoded;
            try (InputStream is = cr.openInputStream(uri)) {
                decoded = (is != null) ? BitmapFactory.decodeStream(is, null, decodeOpts) : null;
            }

            if (decoded != null) {
                CACHE.put(key, decoded);
                targetView.setImageBitmap(decoded);
            } else {
                targetView.setImageResource(R.drawable.ic_image);
            }
        } catch (Exception e) {
            targetView.setImageResource(R.drawable.ic_image);
        }
    }
}
