// SPDX-License-Identifier: Apache-2.0
package com.wireguard.android.hotspot;

import android.os.ParcelFileDescriptor;
import android.os.IBinder;

interface IHotspotService {
    ParcelFileDescriptor prepare(in String[] addresses, in String[] dns, int mtu, IBinder owner) = 0;
    void startHotspot() = 1;
    String status() = 2;
    void stop() = 3;
    void recoverStop() = 4;
    void destroy() = 16777114;
}
