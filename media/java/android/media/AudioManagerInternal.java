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
package android.media;

import android.util.IntArray;
import com.android.server.LocalServices;

/**
 * Class for system services to access extra AudioManager functionality. The
 * AudioService is responsible for registering an implementation with
 * {@link LocalServices}.
 *
 * @hide
 */
public abstract class AudioManagerInternal {

    public abstract void adjustSuggestedStreamVolumeForUid(int streamType, int direction,
            int flags, String callingPackage, int uid);

    public abstract void adjustStreamVolumeForUid(int streamType, int direction, int flags,
            String callingPackage, int uid);

    public abstract void setStreamVolumeForUid(int streamType, int direction, int flags,
            String callingPackage, int uid);

    public abstract void setRingerModeDelegate(RingerModeDelegate delegate);

    public abstract int getRingerModeInternal();

    public abstract void setRingerModeInternal(int ringerMode, String caller);

    public abstract void updateRingerModeAffectedStreamsInternal();

    public abstract void setAccessibilityServiceUids(IntArray uids);

    public interface RingerModeDelegate {
        /** Called when external ringer mode is evaluated, returns the new internal ringer mode */
        int onSetRingerModeExternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeInternal, VolumePolicy policy);

        /** Called when internal ringer mode is evaluated, returns the new external ringer mode */
        int onSetRingerModeInternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeExternal, VolumePolicy policy);

        boolean canVolumeDownEnterSilent();

        int getRingerModeAffectedStreams(int streams);
    }

    /**
     * Disable or restore the ability to play audio for a given UID.
     * When a UID isn't meant to be tracked anymore (e.g. client died), re-enable audio for this UID
     * to prevent disabling audio for future UIDs that would reuse the same value.
     * This operation is asynchronous.
     * @param disable when true, prevents playback of audio streams from the given uid. If false,
     *         restores the ability to play, or no-op if playback hadn't been disabled before.
     * @param uid the client UID whose ability to play will be affected.
     */
    public abstract void disableAudioForUid(boolean disable, int uid);
}
