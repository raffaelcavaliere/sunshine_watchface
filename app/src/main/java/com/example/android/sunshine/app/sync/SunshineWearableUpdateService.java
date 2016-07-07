package com.example.android.sunshine.app.sync;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Created by raffaelcavaliere on 2016-07-02.
 */
public class SunshineWearableUpdateService extends IntentService implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private GoogleApiClient mGoogleApiClient;
    public final String LOG_TAG = SunshineWearableUpdateService.class.getSimpleName();
    private static final String WEATHER_PATH = "/weather";

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;

    public SunshineWearableUpdateService() {
        super("SunshineWatchfaceUpdateService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (SunshineSyncAdapter.ACTION_DATA_UPDATED.equals(action)) {
                mGoogleApiClient = new GoogleApiClient.Builder(SunshineWearableUpdateService.this, this, this)
                        .addApi(Wearable.API)
                        .build();
                mGoogleApiClient.connect();
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {

        String locationQuery = Utility.getPreferredLocation(this);
        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());
        Cursor cursor = getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            double low = cursor.getDouble(INDEX_MIN_TEMP);
            String desc = cursor.getString(INDEX_SHORT_DESC);

            PutDataMapRequest putDataMapReq = PutDataMapRequest.create(WEATHER_PATH);
            DataMap putMap = putDataMapReq.getDataMap();
            putMap.putLong("time", System.currentTimeMillis());
            putMap.putString("location", locationQuery);
            putMap.putInt(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID, weatherId);
            putMap.putDouble(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP, high);
            putMap.putDouble(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP, low);
            putMap.putString(WeatherContract.WeatherEntry.COLUMN_SHORT_DESC, desc);

            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
            Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq)
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        if (!dataItemResult.getStatus().isSuccess()) {
                            Log.d(LOG_TAG, "Weather data result callback : FAILED");
                        } else {
                            Log.d(LOG_TAG, "Weather data result callback : SUCCESS");
                        }
                    }
                });
        }
        else {
            Log.d(LOG_TAG, "Weather query did not return data!");
        }

        cursor.close();
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "Google API client connection suspended: " + i);
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(LOG_TAG, "Google API client failed to connect: " + connectionResult);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if(mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }
}
