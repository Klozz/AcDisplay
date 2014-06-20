/*
 * Copyright (C) 2014 AChep@xda <artemchep@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package com.achep.acdisplay.services.activemode.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

import com.achep.acdisplay.Build;
import com.achep.acdisplay.services.activemode.ActiveModeSensor;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Basing on results of proximity sensor it notifies when
 * {@link com.achep.acdisplay.acdisplay.AcDisplayActivity AcDisplay}
 * should be shown.
 *
 * @author Artem Chepurnoy
 */
public final class ProximitySensor extends ActiveModeSensor implements
        SensorEventListener {

    private static final String TAG = "ProximitySensor";

    private static final int LAST_EVENT_MAX_TIME = 1000; // ms.

    private static WeakReference<ProximitySensor> sProximitySensorWeak;
    private static long sLastEventTime;
    private static boolean sAttached;
    private static boolean sNear;

    private float mMaximumRange;
    private boolean mFirstChange;

    private final ArrayList<Program> mPrograms;
    private final ArrayList<Event> mHistory;
    private final Handler mHandler;
    private int mHistoryMaximumSize;

    private static class Program {

        final Data[] mDatas;

        private static class Data {
            final boolean isNear;
            final int timeMin;
            final long timeMax;

            public Data(boolean isNear, int timeMin, long timeMax) {
                this.isNear = isNear;
                this.timeMin = timeMin;
                this.timeMax = timeMax;
            }
        }

        private Program(Data[] datas) {
            mDatas = datas;
        }

        public int fits(ArrayList<Event> history) {
            int historySize = history.size();
            int programSize = mDatas.length;
            if (historySize < programSize) {
                return -1;
            }

            int historyOffset = historySize - programSize;
            Event eventPrevious = history.get(historyOffset);

            for (int i = 1; i < programSize; i++) {
                Data data = mDatas[i - 1];
                Event eventFuture = history.get(historyOffset + i);

                final long delta = eventFuture.time - eventPrevious.time;

                if (eventPrevious.isNear != data.isNear
                        || delta <= data.timeMin
                        || delta >= data.timeMax) {
                    return -1;
                }

                eventPrevious = eventFuture;
            }

            Data data = mDatas[programSize - 1];
            if (eventPrevious.isNear == data.isNear) {
                return data.timeMin;
            }

            return -1;
        }

        public static class Builder {

            private final ArrayList<Data> mProgram = new ArrayList<>(10);
            private boolean mLastNear;

            public Builder begin(boolean isNear, int timeMin) {
                return add(isNear, timeMin, Long.MAX_VALUE);
            }

            public Builder add(int timeMin, long timeMax) {
                return add(!mLastNear, timeMin, timeMax);
            }

            public Builder end(int timeMin) {
                return add(timeMin, 0);
            }

            private Builder add(boolean isNear, int timeMin, long timeMax) {
                Data data = new Data(isNear, timeMin, timeMax);
                mProgram.add(data);
                mLastNear = isNear;
                return this;
            }

            public Program build() {
                return new Program(mProgram.toArray(new Data[mProgram.size()]));
            }

        }

    }

    /**
     * Proximity event.
     */
    private static class Event {
        final boolean isNear;
        final long time;

        public Event(boolean isNear, long time) {
            this.isNear = isNear;
            this.time = time;
        }

    }

    private ProximitySensor() {
        super();
        Program programPocket = new Program.Builder()
                .begin(true, 3000) /* is near at least for 3 seconds */
                .end(0) /* and after: is far  at least for 0 seconds */
                .build();
        Program programWave2Wake = new Program.Builder()
                .begin(true, 200) /*        is near at least for 200 millis */
                .add(0, 1000) /* and after: is far  not more than 1 second  */
                .add(0, 1000) /* and after: is near not more than 1 second  */
                .end(0)       /* and after: is far  at least for  0 second  */
                .build();

        mPrograms = new ArrayList<>();
        mPrograms.add(programWave2Wake);
        mPrograms.add(programPocket);

        for (Program program : mPrograms) {
            int size = program.mDatas.length;
            if (size > mHistoryMaximumSize) {
                mHistoryMaximumSize = size;
            }
        }

        mHistory = new ArrayList<>(mHistoryMaximumSize);
        mHandler = new Handler();
    }

    public static ProximitySensor getInstance() {
        ProximitySensor sensor = sProximitySensorWeak != null
                ? sProximitySensorWeak.get() : null;
        if (sensor == null) {
            sensor = new ProximitySensor();
            sProximitySensorWeak = new WeakReference<>(sensor);
        }
        return sensor;
    }

    /**
     * @return {@code true} if sensor is currently in "near" state, and {@code false} otherwise.
     */
    public static boolean isNear() {
        return (getTimeNow() - sLastEventTime < LAST_EVENT_MAX_TIME || sAttached) && sNear;
    }

    @Override
    public int getType() {
        return Sensor.TYPE_PROXIMITY;
    }

    @Override
    public void onStart() {
        if (Build.DEBUG) Log.d(TAG, "Starting proximity sensor...");

        SensorManager sensorManager = getSensorManager();
        Sensor proximitySensor = sensorManager.getDefaultSensor(getType());
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);

        mMaximumRange = proximitySensor.getMaximumRange();
        mHistory.clear();
        mHistory.add(new Event(false, getTimeNow()));

        sAttached = true;
        mFirstChange = true;
    }

    @Override
    public void onStop() {
        if (Build.DEBUG) Log.d(TAG, "Stopping proximity sensor...");

        SensorManager sensorManager = getSensorManager();
        sensorManager.unregisterListener(this);
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        final float distance = event.values[0];
        final boolean isNear = distance < mMaximumRange || distance < 1.0f;
        final boolean changed = sNear != (sNear = isNear) || mFirstChange;

        long now = getTimeNow();
        if (Build.DEBUG) {
            int historySize = mHistory.size();
            String delta = (historySize > 0
                    ? " delta=" + (now - mHistory.get(historySize - 1).time)
                    : " first_event");
            Log.d(TAG + ":SensorEvent", "distance=" + distance
                    + " is_near=" + isNear
                    + " changed=" + changed
                    + delta);
        }

        if (!changed) {
            // Well just in cause if proximity sensor is NOT always eventual.
            // This should not happen, but who knows... I found maximum
            // range buggy enough.
            return;
        }

        if (mHistory.size() >= mHistoryMaximumSize)
            mHistory.remove(0);

        mHandler.removeCallbacksAndMessages(null);
        mHistory.add(new Event(isNear, now));
        for (Program program : mPrograms) {
            int delay = program.fits(mHistory);
            if (delay >= 0) {
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mHandler.removeCallbacksAndMessages(null);
                        mHistory.clear();
                        requestWakeUp();
                    }
                }, delay);
            }
        }

        sLastEventTime = now;
        mFirstChange = false;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* unused */ }

}
