/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineFace extends CanvasWatchFaceService {

    private static final String TAG = SunshineFace.class.getSimpleName();
    private static final String WEATHER_UPDATE_PATH = "/weather_update";
    private static final String WEATHER_PATH = "/weather";
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update twice a second
     * to blink the colons.
     */

    private static final long NORMAL_UPDATE_RATE_MS = 500;
    private static final long AMBIENT_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    private GoogleApiClient mGoogleApiClient;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        static final String COLON_STRING = ":";

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        static final int MSG_UPDATE_TIME = 0;

        /** How often {@link #mUpdateTimeHandler} ticks in milliseconds. */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        double weather_low, weather_high;
        String weather_location, weather_desc;
        int weather_id;
        Bitmap weather_icon;

        /** Handler to update the time periodically in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mSecondPaint;
        Paint mAmPmPaint;
        Paint mColonPaint;
        float mColonWidth;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        Paint mLocationPaint;

        boolean mAmbient;

        Calendar mCalendar;

        boolean mShouldDrawColons;
        float mYOffset;
        float mLineHeight;
        float mHorizontalLineWidth;
        float mHorizontalLinePadding;
        float mTempPadding;
        String mAmString;
        String mPmString;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineFace.this, this, this)
            .addApi(Wearable.API)
            .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setStatusBarGravity(Gravity.TOP | Gravity.LEFT)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.RIGHT)
                    .build());
            Resources resources = SunshineFace.this.getResources();

            mLineHeight = resources.getDimension(R.dimen.digital_line_height);
            mHorizontalLineWidth = resources.getDimension(R.dimen.digital_horizontal_line_width);
            mHorizontalLinePadding = resources.getDimension(R.dimen.digital_horizontal_line_padding);
            mTempPadding = resources.getDimension(R.dimen.digital_temp_padding);
            mAmString = resources.getString(R.string.time_am);
            mPmString = resources.getString(R.string.time_pm);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.sunshine_blue));
            mDatePaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.date_color));
            mHourPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.hour_color), BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.minute_color));
            mSecondPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.second_color));
            mAmPmPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.am_pm_color));
            mColonPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.colons_color));
            mMinTempPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.low_color));
            mMaxTempPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.high_color), BOLD_TYPEFACE);
            mLocationPaint = createTextPaint(ContextCompat.getColor(getApplicationContext(), R.color.date_color));

            mCalendar = Calendar.getInstance();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mYOffset = isRound ? resources.getDimension(R.dimen.digital_y_offset_round) : resources.getDimension(R.dimen.digital_y_offset);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mSecondPaint.setTextSize(textSize);
            mAmPmPaint.setTextSize(resources.getDimension(R.dimen.digital_am_pm_text_size));
            mColonPaint.setTextSize(textSize);
            mMinTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_text_size));
            mMaxTempPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_text_size));
            mLocationPaint.setTextSize(resources.getDimension(R.dimen.digital_location_text_size));

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineFace.this);
            int hour = mCalendar.get(Calendar.HOUR);
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            String hourString;
            if (is24Hour) {
                hourString = String.format("%02d", mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            String minuteString = String.format("%02d", mCalendar.get(Calendar.MINUTE));
            String amPmString = !is24Hour ? mCalendar.get(Calendar.AM_PM) == Calendar.PM ? mPmString : mAmString : "";

            float measuredTime = (mHourPaint.measureText(hourString) + mColonWidth + mMinutePaint.measureText(minuteString) + mColonWidth + mAmPmPaint.measureText(amPmString));
            float x = bounds.centerX() - (measuredTime / 2);
            float y = mYOffset;

            // Draw hours
            canvas.drawText(hourString, x, y, mHourPaint);
            x += mHourPaint.measureText(hourString);

            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, y - 5, mColonPaint);
            }
            x += mColonWidth;

            // Draw minutes
            canvas.drawText(minuteString, x, y, mMinutePaint);

            // Draw AM or PM
            x += mMinutePaint.measureText(minuteString) + mColonWidth;
            canvas.drawText(amPmString, x, y, mAmPmPaint);

            SimpleDateFormat dateFormat = new SimpleDateFormat("EE, MMM d yyyy");
            String dateString = dateFormat.format(mCalendar.getTime());
            float measuredDate = mDatePaint.measureText(dateString);
            x = bounds.centerX() - (measuredDate / 2);
            y += mLineHeight;

            // Draw date
            canvas.drawText(dateString.toUpperCase(), x, y, mDatePaint);
            y += mHorizontalLinePadding;

            // Draw horizontal line
            canvas.drawLine(bounds.centerX() - mHorizontalLineWidth / 2, mYOffset + mLineHeight + mHorizontalLinePadding,
                    bounds.centerX() + mHorizontalLineWidth / 2, mYOffset + mLineHeight + mHorizontalLinePadding, mDatePaint);

            String highString = String.valueOf(Math.round(weather_high)) + "°";
            String lowString = String.valueOf(Math.round(weather_low)) + "°";

            float measuredTemp = mMaxTempPaint.measureText(highString) + mColonWidth + mMinTempPaint.measureText(lowString);
            if (weather_icon != null)
                measuredTemp += weather_icon.getWidth() + mColonWidth;

            x = bounds.centerX() - (measuredTemp / 2);
            y += mTempPadding;

            //Draw bitmap
            if (weather_icon != null) {
                canvas.drawBitmap(weather_icon, x, y - weather_icon.getHeight() + (mMaxTempPaint.getTextSize() / 2), null);
                x += weather_icon.getWidth() + mColonWidth;
            }

            //Draw high
            canvas.drawText(highString, x, y, mMaxTempPaint);
            x += mMaxTempPaint.measureText(highString) + mColonWidth;

            //Draw low
            canvas.drawText(lowString, x, y, mMinTempPaint);
            y += mLineHeight;

            //Draw location
            if (weather_location != null) {
                float measuredLocation = mLocationPaint.measureText(weather_location);
                x = bounds.centerX() - (measuredLocation / 2);
                canvas.drawText(weather_location, x, y, mLocationPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = mInteractiveUpdateRateMs
                        - (timeMs % mInteractiveUpdateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    String path = item.getUri().getPath();
                    if (path.compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        weather_location = dataMap.getString("location");
                        weather_high = dataMap.getDouble(SunshineFaceUtils.COLUMN_MAX_TEMP);
                        weather_low = dataMap.getDouble(SunshineFaceUtils.COLUMN_MIN_TEMP);
                        weather_desc = dataMap.getString(SunshineFaceUtils.COLUMN_SHORT_DESC);
                        weather_id = dataMap.getInt(SunshineFaceUtils.COLUMN_WEATHER_ID);
                        Drawable d = getResources().getDrawable(SunshineFaceUtils.getIconResourceForWeatherCondition(weather_id), getTheme());
                        if (d != null) {
                            Bitmap b = ((BitmapDrawable) d).getBitmap();
                            float width = (mHourPaint.getTextSize() / b.getHeight()) * b.getWidth();
                            weather_icon = Bitmap.createScaledBitmap(b, (int) width, (int) mHourPaint.getTextSize(), true);
                        }

                        Log.d(TAG, path + " " + dataMap.getLong("time") + " " + weather_location + " " + weather_high + " " + weather_low + " " + weather_desc + " " + weather_id);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.d(TAG, "Sunshine Watchface connected to wearable API");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            //ask for current weather data
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_UPDATE_PATH);
            putDataMapRequest.getDataMap().putString("uuid", UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Weather data result callback : FAILED");
                            } else {
                                Log.d(TAG, "Weather data result callback : SUCCESS");
                            }
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Google API client connection suspended: " + i);
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(TAG, "Google API client failed to connect: " + connectionResult);
        }
    }
}
