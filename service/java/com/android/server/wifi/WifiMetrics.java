/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.NetworkAgent;
import android.net.wifi.EAPConstants;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.aware.WifiAwareMetrics;
import com.android.server.wifi.hotspot2.ANQPNetworkKey;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.PasspointProvider;
import com.android.server.wifi.hotspot2.Utils;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.nano.WifiMetricsProto.ConnectToNetworkNotificationAndActionCount;
import com.android.server.wifi.nano.WifiMetricsProto.ExperimentValues;
import com.android.server.wifi.nano.WifiMetricsProto.LinkSpeedCount;
import com.android.server.wifi.nano.WifiMetricsProto.PnoScanMetrics;
import com.android.server.wifi.nano.WifiMetricsProto.SoftApConnectedClientsEvent;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.nano.WifiMetricsProto.StaEvent.ConfigInfo;
import com.android.server.wifi.nano.WifiMetricsProto.WifiIsUnusableEvent;
import com.android.server.wifi.nano.WifiMetricsProto.WifiLinkLayerUsageStats;
import com.android.server.wifi.nano.WifiMetricsProto.WpsMetrics;
import com.android.server.wifi.rtt.RttMetrics;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.ScanResultUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides storage for wireless connectivity metrics, as they are generated.
 * Metrics logged by this class include:
 *   Aggregated connection stats (num of connections, num of failures, ...)
 *   Discrete connection event stats (time, duration, failure codes, ...)
 *   Router details (technology type, authentication type, ...)
 *   Scan stats
 */
public class WifiMetrics {
    private static final String TAG = "WifiMetrics";
    private static final boolean DBG = false;
    /**
     * Clamp the RSSI poll counts to values between [MIN,MAX]_RSSI_POLL
     */
    private static final int MAX_RSSI_POLL = 0;
    private static final int MIN_RSSI_POLL = -127;
    public static final int MAX_RSSI_DELTA = 127;
    public static final int MIN_RSSI_DELTA = -127;
    /** Minimum link speed (Mbps) to count for link_speed_counts */
    public static final int MIN_LINK_SPEED_MBPS = 0;
    /** Maximum time period between ScanResult and RSSI poll to generate rssi delta datapoint */
    public static final long TIMEOUT_RSSI_DELTA_MILLIS =  3000;
    private static final int MIN_WIFI_SCORE = 0;
    private static final int MAX_WIFI_SCORE = NetworkAgent.WIFI_BASE_SCORE;
    @VisibleForTesting
    static final int LOW_WIFI_SCORE = 50; // Mobile data score
    private final Object mLock = new Object();
    private static final int MAX_CONNECTION_EVENTS = 256;
    // Largest bucket in the NumConnectableNetworkCount histogram,
    // anything large will be stored in this bucket
    public static final int MAX_CONNECTABLE_SSID_NETWORK_BUCKET = 20;
    public static final int MAX_CONNECTABLE_BSSID_NETWORK_BUCKET = 50;
    public static final int MAX_TOTAL_SCAN_RESULT_SSIDS_BUCKET = 100;
    public static final int MAX_TOTAL_SCAN_RESULTS_BUCKET = 250;
    public static final int MAX_TOTAL_PASSPOINT_APS_BUCKET = 50;
    public static final int MAX_TOTAL_PASSPOINT_UNIQUE_ESS_BUCKET = 20;
    public static final int MAX_PASSPOINT_APS_PER_UNIQUE_ESS_BUCKET = 50;
    public static final int MAX_TOTAL_80211MC_APS_BUCKET = 20;
    private static final int CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER = 1000;
    // Max limit for number of soft AP related events, extra events will be dropped.
    private static final int MAX_NUM_SOFT_AP_EVENTS = 256;
    // Maximum number of WifiIsUnusableEvent
    public static final int MAX_UNUSABLE_EVENTS = 20;
    // Minimum time wait before generating next WifiIsUnusableEvent from data stall
    public static final int MIN_DATA_STALL_WAIT_MS = 120 * 1000; // 2 minutes
    private static final int WIFI_IS_UNUSABLE_EVENT_METRICS_ENABLED_DEFAULT = 1; // 1 = true
    private static final int WIFI_LINK_SPEED_METRICS_ENABLED_DEFAULT = 1; // 1 = true

    private Clock mClock;
    private boolean mScreenOn;
    private int mWifiState;
    private WifiAwareMetrics mWifiAwareMetrics;
    private RttMetrics mRttMetrics;
    private final PnoScanMetrics mPnoScanMetrics = new PnoScanMetrics();
    private final WifiLinkLayerUsageStats mWifiLinkLayerUsageStats = new WifiLinkLayerUsageStats();
    private final WpsMetrics mWpsMetrics = new WpsMetrics();
    private final ExperimentValues mExperimentValues = new ExperimentValues();
    private Handler mHandler;
    private ScoringParams mScoringParams;
    private WifiConfigManager mWifiConfigManager;
    private WifiNetworkSelector mWifiNetworkSelector;
    private PasspointManager mPasspointManager;
    private Context mContext;
    private FrameworkFacade mFacade;
    private WifiDataStall mWifiDataStall;
    private WifiLinkLayerStats mLastLinkLayerStats;

    /** Tracks if we should be logging WifiIsUnusableEvent */
    private boolean mUnusableEventLogging = false;
    /** Tracks if we should be logging LinkSpeedCounts */
    private boolean mLinkSpeedCountsLogging = true;

    /**
     * Metrics are stored within an instance of the WifiLog proto during runtime,
     * The ConnectionEvent, SystemStateEntries & ScanReturnEntries metrics are stored during
     * runtime in member lists of this WifiMetrics class, with the final WifiLog proto being pieced
     * together at dump-time
     */
    private final WifiMetricsProto.WifiLog mWifiLogProto = new WifiMetricsProto.WifiLog();
    /**
     * Session information that gets logged for every Wifi connection attempt.
     */
    private final List<ConnectionEvent> mConnectionEventList = new ArrayList<>();
    /**
     * The latest started (but un-ended) connection attempt
     */
    private ConnectionEvent mCurrentConnectionEvent;
    /**
     * Count of number of times each scan return code, indexed by WifiLog.ScanReturnCode
     */
    private final SparseIntArray mScanReturnEntries = new SparseIntArray();
    /**
     * Mapping of system state to the counts of scans requested in that wifi state * screenOn
     * combination. Indexed by WifiLog.WifiState * (1 + screenOn)
     */
    private final SparseIntArray mWifiSystemStateEntries = new SparseIntArray();
    /** Mapping of channel frequency to its RSSI distribution histogram **/
    private final Map<Integer, SparseIntArray> mRssiPollCountsMap = new HashMap<>();
    /** Mapping of RSSI scan-poll delta values to counts. */
    private final SparseIntArray mRssiDeltaCounts = new SparseIntArray();
    /** Mapping of link speed values to LinkSpeedCount objects. */
    private final SparseArray<LinkSpeedCount> mLinkSpeedCounts = new SparseArray<>();
    /** RSSI of the scan result for the last connection event*/
    private int mScanResultRssi = 0;
    /** Boot-relative timestamp when the last candidate scanresult was received, used to calculate
        RSSI deltas. -1 designates no candidate scanResult being tracked */
    private long mScanResultRssiTimestampMillis = -1;
    /** Mapping of alert reason to the respective alert count. */
    private final SparseIntArray mWifiAlertReasonCounts = new SparseIntArray();
    /**
     * Records the getElapsedSinceBootMillis (in seconds) that represents the beginning of data
     * capture for for this WifiMetricsProto
     */
    private long mRecordStartTimeSec;
    /** Mapping of Wifi Scores to counts */
    private final SparseIntArray mWifiScoreCounts = new SparseIntArray();
    /** Mapping of SoftApManager start SoftAp return codes to counts */
    private final SparseIntArray mSoftApManagerReturnCodeCounts = new SparseIntArray();

    private final SparseIntArray mTotalSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mTotalBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenOrSavedSsidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableOpenOrSavedBssidsInScanHistogram = new SparseIntArray();
    private final SparseIntArray mAvailableSavedPasspointProviderProfilesInScanHistogram =
            new SparseIntArray();
    private final SparseIntArray mAvailableSavedPasspointProviderBssidsInScanHistogram =
            new SparseIntArray();

    private final SparseIntArray mInstalledPasspointProfileType = new SparseIntArray();

    /** Mapping of "Connect to Network" notifications to counts. */
    private final SparseIntArray mConnectToNetworkNotificationCount = new SparseIntArray();
    /** Mapping of "Connect to Network" notification user actions to counts. */
    private final SparseIntArray mConnectToNetworkNotificationActionCount = new SparseIntArray();
    private int mOpenNetworkRecommenderBlacklistSize = 0;
    private boolean mIsWifiNetworksAvailableNotificationOn = false;
    private int mNumOpenNetworkConnectMessageFailedToSend = 0;
    private int mNumOpenNetworkRecommendationUpdates = 0;
    /** List of soft AP events related to number of connected clients in tethered mode */
    private final List<SoftApConnectedClientsEvent> mSoftApEventListTethered = new ArrayList<>();
    /** List of soft AP events related to number of connected clients in local only mode */
    private final List<SoftApConnectedClientsEvent> mSoftApEventListLocalOnly = new ArrayList<>();

    private final SparseIntArray mObservedHotspotR1ApInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2ApInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR1EssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2EssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR1ApsPerEssInScanHistogram = new SparseIntArray();
    private final SparseIntArray mObservedHotspotR2ApsPerEssInScanHistogram = new SparseIntArray();

    private final SparseIntArray mObserved80211mcApInScanHistogram = new SparseIntArray();

    /** Wifi power metrics*/
    private WifiPowerMetrics mWifiPowerMetrics;

    /** Wifi Wake metrics */
    private final WifiWakeMetrics mWifiWakeMetrics = new WifiWakeMetrics();

    private boolean mIsMacRandomizationOn = false;

    class RouterFingerPrint {
        private WifiMetricsProto.RouterFingerPrint mRouterFingerPrintProto;
        RouterFingerPrint() {
            mRouterFingerPrintProto = new WifiMetricsProto.RouterFingerPrint();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            synchronized (mLock) {
                sb.append("mConnectionEvent.roamType=" + mRouterFingerPrintProto.roamType);
                sb.append(", mChannelInfo=" + mRouterFingerPrintProto.channelInfo);
                sb.append(", mDtim=" + mRouterFingerPrintProto.dtim);
                sb.append(", mAuthentication=" + mRouterFingerPrintProto.authentication);
                sb.append(", mHidden=" + mRouterFingerPrintProto.hidden);
                sb.append(", mRouterTechnology=" + mRouterFingerPrintProto.routerTechnology);
                sb.append(", mSupportsIpv6=" + mRouterFingerPrintProto.supportsIpv6);
            }
            return sb.toString();
        }
        public void updateFromWifiConfiguration(WifiConfiguration config) {
            synchronized (mLock) {
                if (config != null) {
                    // Is this a hidden network
                    mRouterFingerPrintProto.hidden = config.hiddenSSID;
                    // Config may not have a valid dtimInterval set yet, in which case dtim will be zero
                    // (These are only populated from beacon frame scan results, which are returned as
                    // scan results from the chip far less frequently than Probe-responses)
                    if (config.dtimInterval > 0) {
                        mRouterFingerPrintProto.dtim = config.dtimInterval;
                    }
                    mCurrentConnectionEvent.mConfigSsid = config.SSID;
                    // Get AuthType information from config (We do this again from ScanResult after
                    // associating with BSSID)
                    if (config.allowedKeyManagement != null
                            && config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
                    } else if (config.isEnterprise()) {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
                    } else {
                        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                                .authentication = WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
                    }
                    mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                            .passpoint = config.isPasspoint();
                    // If there's a ScanResult candidate associated with this config already, get it and
                    // log (more accurate) metrics from it
                    ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
                    if (candidate != null) {
                        updateMetricsFromScanResult(candidate);
                    }
                }
            }
        }
    }

    /**
     * Log event, tracking the start time, end time and result of a wireless connection attempt.
     */
    class ConnectionEvent {
        WifiMetricsProto.ConnectionEvent mConnectionEvent;
        //<TODO> Move these constants into a wifi.proto Enum, and create a new Failure Type field
        //covering more than just l2 failures. see b/27652362
        /**
         * Failure codes, used for the 'level_2_failure_code' Connection event field (covers a lot
         * more failures than just l2 though, since the proto does not have a place to log
         * framework failures)
         */
        // Failure is unknown
        public static final int FAILURE_UNKNOWN = 0;
        // NONE
        public static final int FAILURE_NONE = 1;
        // ASSOCIATION_REJECTION_EVENT
        public static final int FAILURE_ASSOCIATION_REJECTION = 2;
        // AUTHENTICATION_FAILURE_EVENT
        public static final int FAILURE_AUTHENTICATION_FAILURE = 3;
        // SSID_TEMP_DISABLED (Also Auth failure)
        public static final int FAILURE_SSID_TEMP_DISABLED = 4;
        // reconnect() or reassociate() call to WifiNative failed
        public static final int FAILURE_CONNECT_NETWORK_FAILED = 5;
        // NETWORK_DISCONNECTION_EVENT
        public static final int FAILURE_NETWORK_DISCONNECTION = 6;
        // NEW_CONNECTION_ATTEMPT before previous finished
        public static final int FAILURE_NEW_CONNECTION_ATTEMPT = 7;
        // New connection attempt to the same network & bssid
        public static final int FAILURE_REDUNDANT_CONNECTION_ATTEMPT = 8;
        // Roam Watchdog timer triggered (Roaming timed out)
        public static final int FAILURE_ROAM_TIMEOUT = 9;
        // DHCP failure
        public static final int FAILURE_DHCP = 10;
        // ASSOCIATION_TIMED_OUT
        public static final int FAILURE_ASSOCIATION_TIMED_OUT = 11;

        RouterFingerPrint mRouterFingerPrint;
        private long mRealStartTime;
        private long mRealEndTime;
        private String mConfigSsid;
        private String mConfigBssid;
        private int mWifiState;
        private boolean mScreenOn;

        private ConnectionEvent() {
            mConnectionEvent = new WifiMetricsProto.ConnectionEvent();
            mRealEndTime = 0;
            mRealStartTime = 0;
            mRouterFingerPrint = new RouterFingerPrint();
            mConnectionEvent.routerFingerprint = mRouterFingerPrint.mRouterFingerPrintProto;
            mConfigSsid = "<NULL>";
            mConfigBssid = "<NULL>";
            mWifiState = WifiMetricsProto.WifiLog.WIFI_UNKNOWN;
            mScreenOn = false;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("startTime=");
            Calendar c = Calendar.getInstance();
            synchronized (mLock) {
                c.setTimeInMillis(mConnectionEvent.startTimeMillis);
                sb.append(mConnectionEvent.startTimeMillis == 0 ? "            <null>" :
                        String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
                sb.append(", SSID=");
                sb.append(mConfigSsid);
                sb.append(", BSSID=");
                sb.append(mConfigBssid);
                sb.append(", durationMillis=");
                sb.append(mConnectionEvent.durationTakenToConnectMillis);
                sb.append(", roamType=");
                switch(mConnectionEvent.roamType) {
                    case 1:
                        sb.append("ROAM_NONE");
                        break;
                    case 2:
                        sb.append("ROAM_DBDC");
                        break;
                    case 3:
                        sb.append("ROAM_ENTERPRISE");
                        break;
                    case 4:
                        sb.append("ROAM_USER_SELECTED");
                        break;
                    case 5:
                        sb.append("ROAM_UNRELATED");
                        break;
                    default:
                        sb.append("ROAM_UNKNOWN");
                }
                sb.append(", connectionResult=");
                sb.append(mConnectionEvent.connectionResult);
                sb.append(", level2FailureCode=");
                switch(mConnectionEvent.level2FailureCode) {
                    case FAILURE_NONE:
                        sb.append("NONE");
                        break;
                    case FAILURE_ASSOCIATION_REJECTION:
                        sb.append("ASSOCIATION_REJECTION");
                        break;
                    case FAILURE_AUTHENTICATION_FAILURE:
                        sb.append("AUTHENTICATION_FAILURE");
                        break;
                    case FAILURE_SSID_TEMP_DISABLED:
                        sb.append("SSID_TEMP_DISABLED");
                        break;
                    case FAILURE_CONNECT_NETWORK_FAILED:
                        sb.append("CONNECT_NETWORK_FAILED");
                        break;
                    case FAILURE_NETWORK_DISCONNECTION:
                        sb.append("NETWORK_DISCONNECTION");
                        break;
                    case FAILURE_NEW_CONNECTION_ATTEMPT:
                        sb.append("NEW_CONNECTION_ATTEMPT");
                        break;
                    case FAILURE_REDUNDANT_CONNECTION_ATTEMPT:
                        sb.append("REDUNDANT_CONNECTION_ATTEMPT");
                        break;
                    case FAILURE_ROAM_TIMEOUT:
                        sb.append("ROAM_TIMEOUT");
                        break;
                    case FAILURE_DHCP:
                        sb.append("DHCP");
                        break;
                    case FAILURE_ASSOCIATION_TIMED_OUT:
                        sb.append("ASSOCIATION_TIMED_OUT");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", connectivityLevelFailureCode=");
                switch(mConnectionEvent.connectivityLevelFailureCode) {
                    case WifiMetricsProto.ConnectionEvent.HLF_NONE:
                        sb.append("NONE");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_DHCP:
                        sb.append("DHCP");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_NO_INTERNET:
                        sb.append("NO_INTERNET");
                        break;
                    case WifiMetricsProto.ConnectionEvent.HLF_UNWANTED:
                        sb.append("UNWANTED");
                        break;
                    default:
                        sb.append("UNKNOWN");
                        break;
                }
                sb.append(", signalStrength=");
                sb.append(mConnectionEvent.signalStrength);
                sb.append(", wifiState=");
                switch(mWifiState) {
                    case WifiMetricsProto.WifiLog.WIFI_DISABLED:
                        sb.append("WIFI_DISABLED");
                        break;
                    case WifiMetricsProto.WifiLog.WIFI_DISCONNECTED:
                        sb.append("WIFI_DISCONNECTED");
                        break;
                    case WifiMetricsProto.WifiLog.WIFI_ASSOCIATED:
                        sb.append("WIFI_ASSOCIATED");
                        break;
                    default:
                        sb.append("WIFI_UNKNOWN");
                        break;
                }
                sb.append(", screenOn=");
                sb.append(mScreenOn);
                sb.append(". mRouterFingerprint: ");
                sb.append(mRouterFingerPrint.toString());
            }
            return sb.toString();
        }
    }

    public WifiMetrics(Context context, FrameworkFacade facade, Clock clock, Looper looper,
            WifiAwareMetrics awareMetrics, RttMetrics rttMetrics,
            WifiPowerMetrics wifiPowerMetrics) {
        mContext = context;
        mFacade = facade;
        mClock = clock;
        mCurrentConnectionEvent = null;
        mScreenOn = true;
        mWifiState = WifiMetricsProto.WifiLog.WIFI_DISABLED;
        mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;
        mWifiAwareMetrics = awareMetrics;
        mRttMetrics = rttMetrics;
        mWifiPowerMetrics = wifiPowerMetrics;

        loadSettings();
        mHandler = new Handler(looper) {
            public void handleMessage(Message msg) {
                synchronized (mLock) {
                    processMessage(msg);
                }
            }
        };
    }

    /**
     * Load setting values related to metrics logging.
     */
    @VisibleForTesting
    public void loadSettings() {
        int unusableEventFlag = mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_IS_UNUSABLE_EVENT_METRICS_ENABLED,
                WIFI_IS_UNUSABLE_EVENT_METRICS_ENABLED_DEFAULT);
        mUnusableEventLogging = (unusableEventFlag == 1);
        setWifiIsUnusableLoggingEnabled(mUnusableEventLogging);
        int linkSpeedCountsFlag = mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_LINK_SPEED_METRICS_ENABLED,
                WIFI_LINK_SPEED_METRICS_ENABLED_DEFAULT);
        mLinkSpeedCountsLogging = (linkSpeedCountsFlag == 1);
        setLinkSpeedCountsLoggingEnabled(mLinkSpeedCountsLogging);
        if (mWifiDataStall != null) {
            mWifiDataStall.loadSettings();
        }
    }

    /** Sets internal ScoringParams member */
    public void setScoringParams(ScoringParams scoringParams) {
        mScoringParams = scoringParams;
    }

    /** Sets internal WifiConfigManager member */
    public void setWifiConfigManager(WifiConfigManager wifiConfigManager) {
        mWifiConfigManager = wifiConfigManager;
    }

    /** Sets internal WifiNetworkSelector member */
    public void setWifiNetworkSelector(WifiNetworkSelector wifiNetworkSelector) {
        mWifiNetworkSelector = wifiNetworkSelector;
    }

    /** Sets internal PasspointManager member */
    public void setPasspointManager(PasspointManager passpointManager) {
        mPasspointManager = passpointManager;
    }

    /** Sets internal WifiDataStall member */
    public void setWifiDataStall(WifiDataStall wifiDataStall) {
        mWifiDataStall = wifiDataStall;
    }

    /**
     * Increment cumulative counters for link layer stats.
     * @param newStats
     */
    public void incrementWifiLinkLayerUsageStats(WifiLinkLayerStats newStats) {
        if (newStats == null) {
            return;
        }
        if (mLastLinkLayerStats == null) {
            mLastLinkLayerStats = newStats;
            return;
        }
        if (!newLinkLayerStatsIsValid(mLastLinkLayerStats, newStats)) {
            // This could mean the radio chip is reset or the data is incorrectly reported.
            // Don't increment any counts and discard the possibly corrupt |newStats| completely.
            mLastLinkLayerStats = null;
            return;
        }
        mWifiLinkLayerUsageStats.loggingDurationMs +=
                (newStats.timeStampInMs - mLastLinkLayerStats.timeStampInMs);
        mWifiLinkLayerUsageStats.radioOnTimeMs += (newStats.on_time - mLastLinkLayerStats.on_time);
        mWifiLinkLayerUsageStats.radioTxTimeMs += (newStats.tx_time - mLastLinkLayerStats.tx_time);
        mWifiLinkLayerUsageStats.radioRxTimeMs += (newStats.rx_time - mLastLinkLayerStats.rx_time);
        mWifiLinkLayerUsageStats.radioScanTimeMs +=
                (newStats.on_time_scan - mLastLinkLayerStats.on_time_scan);
        mLastLinkLayerStats = newStats;
    }

    private boolean newLinkLayerStatsIsValid(WifiLinkLayerStats oldStats,
            WifiLinkLayerStats newStats) {
        if (newStats.on_time < oldStats.on_time
                || newStats.tx_time < oldStats.tx_time
                || newStats.rx_time < oldStats.rx_time
                || newStats.on_time_scan < oldStats.on_time_scan) {
            return false;
        }
        return true;
    }

    /**
     * Increment total number of attempts to start a pno scan
     */
    public void incrementPnoScanStartAttempCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanAttempts++;
        }
    }

    /**
     * Increment total number of attempts with pno scan failed
     */
    public void incrementPnoScanFailedCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanFailed++;
        }
    }

    /**
     * Increment number of pno scans started successfully over offload
     */
    public void incrementPnoScanStartedOverOffloadCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanStartedOverOffload++;
        }
    }

    /**
     * Increment number of pno scans failed over offload
     */
    public void incrementPnoScanFailedOverOffloadCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoScanFailedOverOffload++;
        }
    }

    /**
     * Increment number of times pno scan found a result
     */
    public void incrementPnoFoundNetworkEventCount() {
        synchronized (mLock) {
            mPnoScanMetrics.numPnoFoundNetworkEvents++;
        }
    }

    /**
     * Increment total number of wps connection attempts
     */
    public void incrementWpsAttemptCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsAttempts++;
        }
    }

    /**
     * Increment total number of wps connection success
     */
    public void incrementWpsSuccessCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsSuccess++;
        }
    }

    /**
     * Increment total number of wps failure on start
     */
    public void incrementWpsStartFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsStartFailure++;
        }
    }

    /**
     * Increment total number of wps overlap failure
     */
    public void incrementWpsOverlapFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsOverlapFailure++;
        }
    }

    /**
     * Increment total number of wps timeout failure
     */
    public void incrementWpsTimeoutFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsTimeoutFailure++;
        }
    }

    /**
     * Increment total number of other wps failure during connection
     */
    public void incrementWpsOtherConnectionFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsOtherConnectionFailure++;
        }
    }

    /**
     * Increment total number of supplicant failure after wps
     */
    public void incrementWpsSupplicantFailureCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsSupplicantFailure++;
        }
    }

    /**
     * Increment total number of wps cancellation
     */
    public void incrementWpsCancellationCount() {
        synchronized (mLock) {
            mWpsMetrics.numWpsCancellation++;
        }
    }

    // Values used for indexing SystemStateEntries
    private static final int SCREEN_ON = 1;
    private static final int SCREEN_OFF = 0;

    /**
     * Create a new connection event. Call when wifi attempts to make a new network connection
     * If there is a current 'un-ended' connection event, it will be ended with UNKNOWN connectivity
     * failure code.
     * Gathers and sets the RouterFingerPrint data as well
     *
     * @param config WifiConfiguration of the config used for the current connection attempt
     * @param roamType Roam type that caused connection attempt, see WifiMetricsProto.WifiLog.ROAM_X
     */
    public void startConnectionEvent(WifiConfiguration config, String targetBSSID, int roamType) {
        synchronized (mLock) {
            // Check if this is overlapping another current connection event
            if (mCurrentConnectionEvent != null) {
                //Is this new Connection Event the same as the current one
                if (mCurrentConnectionEvent.mConfigSsid != null
                        && mCurrentConnectionEvent.mConfigBssid != null
                        && config != null
                        && mCurrentConnectionEvent.mConfigSsid.equals(config.SSID)
                        && (mCurrentConnectionEvent.mConfigBssid.equals("any")
                        || mCurrentConnectionEvent.mConfigBssid.equals(targetBSSID))) {
                    mCurrentConnectionEvent.mConfigBssid = targetBSSID;
                    // End Connection Event due to new connection attempt to the same network
                    endConnectionEvent(ConnectionEvent.FAILURE_REDUNDANT_CONNECTION_ATTEMPT,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE);
                } else {
                    // End Connection Event due to new connection attempt to different network
                    endConnectionEvent(ConnectionEvent.FAILURE_NEW_CONNECTION_ATTEMPT,
                            WifiMetricsProto.ConnectionEvent.HLF_NONE);
                }
            }
            //If past maximum connection events, start removing the oldest
            while(mConnectionEventList.size() >= MAX_CONNECTION_EVENTS) {
                mConnectionEventList.remove(0);
            }
            mCurrentConnectionEvent = new ConnectionEvent();
            mCurrentConnectionEvent.mConnectionEvent.startTimeMillis =
                    mClock.getWallClockMillis();
            mCurrentConnectionEvent.mConfigBssid = targetBSSID;
            mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
            mCurrentConnectionEvent.mRouterFingerPrint.updateFromWifiConfiguration(config);
            mCurrentConnectionEvent.mConfigBssid = "any";
            mCurrentConnectionEvent.mRealStartTime = mClock.getElapsedSinceBootMillis();
            mCurrentConnectionEvent.mWifiState = mWifiState;
            mCurrentConnectionEvent.mScreenOn = mScreenOn;
            mConnectionEventList.add(mCurrentConnectionEvent);
            mScanResultRssiTimestampMillis = -1;
            if (config != null) {
                ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
                if (candidate != null) {
                    // Cache the RSSI of the candidate, as the connection event level is updated
                    // from other sources (polls, bssid_associations) and delta requires the
                    // scanResult rssi
                    mScanResultRssi = candidate.level;
                    mScanResultRssiTimestampMillis = mClock.getElapsedSinceBootMillis();
                }
            }
        }
    }

    /**
     * set the RoamType of the current ConnectionEvent (if any)
     */
    public void setConnectionEventRoamType(int roamType) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                mCurrentConnectionEvent.mConnectionEvent.roamType = roamType;
            }
        }
    }

    /**
     * Set AP related metrics from ScanDetail
     */
    public void setConnectionScanDetail(ScanDetail scanDetail) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null && scanDetail != null) {
                NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                ScanResult scanResult = scanDetail.getScanResult();
                //Ensure that we have a networkDetail, and that it corresponds to the currently
                //tracked connection attempt
                if (networkDetail != null && scanResult != null
                        && mCurrentConnectionEvent.mConfigSsid != null
                        && mCurrentConnectionEvent.mConfigSsid
                        .equals("\"" + networkDetail.getSSID() + "\"")) {
                    updateMetricsFromNetworkDetail(networkDetail);
                    updateMetricsFromScanResult(scanResult);
                }
            }
        }
    }

    /**
     * End a Connection event record. Call when wifi connection attempt succeeds or fails.
     * If a Connection event has not been started and is active when .end is called, a new one is
     * created with zero duration.
     *
     * @param level2FailureCode Level 2 failure code returned by supplicant
     * @param connectivityFailureCode WifiMetricsProto.ConnectionEvent.HLF_X
     */
    public void endConnectionEvent(int level2FailureCode, int connectivityFailureCode) {
        synchronized (mLock) {
            if (mCurrentConnectionEvent != null) {
                boolean result = (level2FailureCode == 1)
                        && (connectivityFailureCode == WifiMetricsProto.ConnectionEvent.HLF_NONE);
                mCurrentConnectionEvent.mConnectionEvent.connectionResult = result ? 1 : 0;
                mCurrentConnectionEvent.mRealEndTime = mClock.getElapsedSinceBootMillis();
                mCurrentConnectionEvent.mConnectionEvent.durationTakenToConnectMillis = (int)
                        (mCurrentConnectionEvent.mRealEndTime
                        - mCurrentConnectionEvent.mRealStartTime);
                mCurrentConnectionEvent.mConnectionEvent.level2FailureCode = level2FailureCode;
                mCurrentConnectionEvent.mConnectionEvent.connectivityLevelFailureCode =
                        connectivityFailureCode;
                // ConnectionEvent already added to ConnectionEvents List. Safe to null current here
                mCurrentConnectionEvent = null;
                if (!result) {
                    mScanResultRssiTimestampMillis = -1;
                }
            }
        }
    }

    /**
     * Set ConnectionEvent DTIM Interval (if set), and 802.11 Connection mode, from NetworkDetail
     */
    private void updateMetricsFromNetworkDetail(NetworkDetail networkDetail) {
        int dtimInterval = networkDetail.getDtimInterval();
        if (dtimInterval > 0) {
            mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.dtim =
                    dtimInterval;
        }
        int connectionWifiMode;
        switch (networkDetail.getWifiMode()) {
            case InformationElementUtil.WifiMode.MODE_UNDEFINED:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_UNKNOWN;
                break;
            case InformationElementUtil.WifiMode.MODE_11A:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_A;
                break;
            case InformationElementUtil.WifiMode.MODE_11B:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_B;
                break;
            case InformationElementUtil.WifiMode.MODE_11G:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_G;
                break;
            case InformationElementUtil.WifiMode.MODE_11N:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_N;
                break;
            case InformationElementUtil.WifiMode.MODE_11AC  :
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_AC;
                break;
            default:
                connectionWifiMode = WifiMetricsProto.RouterFingerPrint.ROUTER_TECH_OTHER;
                break;
        }
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto
                .routerTechnology = connectionWifiMode;
    }

    /**
     * Set ConnectionEvent RSSI and authentication type from ScanResult
     */
    private void updateMetricsFromScanResult(ScanResult scanResult) {
        mCurrentConnectionEvent.mConnectionEvent.signalStrength = scanResult.level;
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                WifiMetricsProto.RouterFingerPrint.AUTH_OPEN;
        mCurrentConnectionEvent.mConfigBssid = scanResult.BSSID;
        if (scanResult.capabilities != null) {
            if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_PERSONAL;
            } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
                mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.authentication =
                        WifiMetricsProto.RouterFingerPrint.AUTH_ENTERPRISE;
            }
        }
        mCurrentConnectionEvent.mRouterFingerPrint.mRouterFingerPrintProto.channelInfo =
                scanResult.frequency;
    }

    void setIsLocationEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isLocationEnabled = enabled;
        }
    }

    void setIsScanningAlwaysEnabled(boolean enabled) {
        synchronized (mLock) {
            mWifiLogProto.isScanningAlwaysEnabled = enabled;
        }
    }

    /**
     * Increment Non Empty Scan Results count
     */
    public void incrementNonEmptyScanResultCount() {
        if (DBG) Log.v(TAG, "incrementNonEmptyScanResultCount");
        synchronized (mLock) {
            mWifiLogProto.numNonEmptyScanResults++;
        }
    }

    /**
     * Increment Empty Scan Results count
     */
    public void incrementEmptyScanResultCount() {
        if (DBG) Log.v(TAG, "incrementEmptyScanResultCount");
        synchronized (mLock) {
            mWifiLogProto.numEmptyScanResults++;
        }
    }

    /**
     * Increment background scan count
     */
    public void incrementBackgroundScanCount() {
        if (DBG) Log.v(TAG, "incrementBackgroundScanCount");
        synchronized (mLock) {
            mWifiLogProto.numBackgroundScans++;
        }
    }

   /**
     * Get Background scan count
     */
    public int getBackgroundScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numBackgroundScans;
        }
    }

    /**
     * Increment oneshot scan count, and the associated WifiSystemScanStateCount entry
     */
    public void incrementOneshotScanCount() {
        synchronized (mLock) {
            mWifiLogProto.numOneshotScans++;
        }
        incrementWifiSystemScanStateCount(mWifiState, mScreenOn);
    }

    /**
     * Increment the count of oneshot scans that include DFS channels.
     */
    public void incrementOneshotScanWithDfsCount() {
        synchronized (mLock) {
            mWifiLogProto.numOneshotHasDfsChannelScans++;
        }
    }

    /**
     * Increment connectivity oneshot scan count.
     */
    public void incrementConnectivityOneshotScanCount() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityOneshotScans++;
        }
    }

    /**
     * Get oneshot scan count
     */
    public int getOneshotScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numOneshotScans;
        }
    }

    /**
     * Get connectivity oneshot scan count
     */
    public int getConnectivityOneshotScanCount() {
        synchronized (mLock) {
            return mWifiLogProto.numConnectivityOneshotScans;
        }
    }

    /**
     * Get the count of oneshot scan requests that included DFS channels.
     */
    public int getOneshotScanWithDfsCount() {
        synchronized (mLock) {
            return mWifiLogProto.numOneshotHasDfsChannelScans;
        }
    }

    /**
     * Increment oneshot scan count for external apps.
     */
    public void incrementExternalAppOneshotScanRequestsCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalAppOneshotScanRequests++;
        }
    }
    /**
     * Increment oneshot scan throttle count for external foreground apps.
     */
    public void incrementExternalForegroundAppOneshotScanRequestsThrottledCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled++;
        }
    }

    /**
     * Increment oneshot scan throttle count for external background apps.
     */
    public void incrementExternalBackgroundAppOneshotScanRequestsThrottledCount() {
        synchronized (mLock) {
            mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled++;
        }
    }

    private String returnCodeToString(int scanReturnCode) {
        switch(scanReturnCode){
            case WifiMetricsProto.WifiLog.SCAN_UNKNOWN:
                return "SCAN_UNKNOWN";
            case WifiMetricsProto.WifiLog.SCAN_SUCCESS:
                return "SCAN_SUCCESS";
            case WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED:
                return "SCAN_FAILURE_INTERRUPTED";
            case WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION:
                return "SCAN_FAILURE_INVALID_CONFIGURATION";
            case WifiMetricsProto.WifiLog.FAILURE_WIFI_DISABLED:
                return "FAILURE_WIFI_DISABLED";
            default:
                return "<UNKNOWN>";
        }
    }

    /**
     * Increment count of scan return code occurrence
     *
     * @param scanReturnCode Return code from scan attempt WifiMetricsProto.WifiLog.SCAN_X
     */
    public void incrementScanReturnEntry(int scanReturnCode, int countToAdd) {
        synchronized (mLock) {
            if (DBG) Log.v(TAG, "incrementScanReturnEntry " + returnCodeToString(scanReturnCode));
            int entry = mScanReturnEntries.get(scanReturnCode);
            entry += countToAdd;
            mScanReturnEntries.put(scanReturnCode, entry);
        }
    }
    /**
     * Get the count of this scanReturnCode
     * @param scanReturnCode that we are getting the count for
     */
    public int getScanReturnEntry(int scanReturnCode) {
        synchronized (mLock) {
            return mScanReturnEntries.get(scanReturnCode);
        }
    }

    private String wifiSystemStateToString(int state) {
        switch(state){
            case WifiMetricsProto.WifiLog.WIFI_UNKNOWN:
                return "WIFI_UNKNOWN";
            case WifiMetricsProto.WifiLog.WIFI_DISABLED:
                return "WIFI_DISABLED";
            case WifiMetricsProto.WifiLog.WIFI_DISCONNECTED:
                return "WIFI_DISCONNECTED";
            case WifiMetricsProto.WifiLog.WIFI_ASSOCIATED:
                return "WIFI_ASSOCIATED";
            default:
                return "default";
        }
    }

    /**
     * Increments the count of scans initiated by each wifi state, accounts for screenOn/Off
     *
     * @param state State of the system when scan was initiated, see WifiMetricsProto.WifiLog.WIFI_X
     * @param screenOn Is the screen on
     */
    public void incrementWifiSystemScanStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            if (DBG) {
                Log.v(TAG, "incrementWifiSystemScanStateCount " + wifiSystemStateToString(state)
                        + " " + screenOn);
            }
            int index = (state * 2) + (screenOn ? SCREEN_ON : SCREEN_OFF);
            int entry = mWifiSystemStateEntries.get(index);
            entry++;
            mWifiSystemStateEntries.put(index, entry);
        }
    }

    /**
     * Get the count of this system State Entry
     */
    public int getSystemStateCount(int state, boolean screenOn) {
        synchronized (mLock) {
            int index = state * 2 + (screenOn ? SCREEN_ON : SCREEN_OFF);
            return mWifiSystemStateEntries.get(index);
        }
    }

    /**
     * Increment number of times the Watchdog of Last Resort triggered, resetting the wifi stack
     */
    public void incrementNumLastResortWatchdogTriggers() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggers++;
        }
    }
    /**
     * @param count number of networks over bad association threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadAssociationNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad authentication threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadAuthenticationNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad dhcp threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadDhcpNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks over bad other threshold when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogBadOtherNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal += count;
        }
    }
    /**
     * @param count number of networks seen when watchdog triggered
     */
    public void addCountToNumLastResortWatchdogAvailableNetworksTotal(int count) {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal += count;
        }
    }
    /**
     * Increment count of triggers with atleast one bad association network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadAssociation() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad authentication network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadAuthentication() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad dhcp network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadDhcp() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp++;
        }
    }
    /**
     * Increment count of triggers with atleast one bad other network
     */
    public void incrementNumLastResortWatchdogTriggersWithBadOther() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogTriggersWithBadOther++;
        }
    }

    /**
     * Increment number of times connectivity watchdog confirmed pno is working
     */
    public void incrementNumConnectivityWatchdogPnoGood() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogPnoGood++;
        }
    }
    /**
     * Increment number of times connectivity watchdog found pno not working
     */
    public void incrementNumConnectivityWatchdogPnoBad() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogPnoBad++;
        }
    }
    /**
     * Increment number of times connectivity watchdog confirmed background scan is working
     */
    public void incrementNumConnectivityWatchdogBackgroundGood() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogBackgroundGood++;
        }
    }
    /**
     * Increment number of times connectivity watchdog found background scan not working
     */
    public void incrementNumConnectivityWatchdogBackgroundBad() {
        synchronized (mLock) {
            mWifiLogProto.numConnectivityWatchdogBackgroundBad++;
        }
    }

    /**
     * Increment various poll related metrics, and cache performance data for StaEvent logging
     */
    public void handlePollResult(WifiInfo wifiInfo) {
        mLastPollRssi = wifiInfo.getRssi();
        mLastPollLinkSpeed = wifiInfo.getLinkSpeed();
        mLastPollFreq = wifiInfo.getFrequency();
        incrementRssiPollRssiCount(mLastPollFreq, mLastPollRssi);
        incrementLinkSpeedCount(mLastPollLinkSpeed, mLastPollRssi);
    }

    /**
     * Increment occurence count of RSSI level from RSSI poll for the given frequency.
     * @param frequency (MHz)
     * @param rssi
     */
    @VisibleForTesting
    public void incrementRssiPollRssiCount(int frequency, int rssi) {
        if (!(rssi >= MIN_RSSI_POLL && rssi <= MAX_RSSI_POLL)) {
            return;
        }
        synchronized (mLock) {
            if (!mRssiPollCountsMap.containsKey(frequency)) {
                mRssiPollCountsMap.put(frequency, new SparseIntArray());
            }
            SparseIntArray sparseIntArray = mRssiPollCountsMap.get(frequency);
            int count = sparseIntArray.get(rssi);
            sparseIntArray.put(rssi, count + 1);
            maybeIncrementRssiDeltaCount(rssi - mScanResultRssi);
        }
    }

    /**
     * Increment occurence count of difference between scan result RSSI and the first RSSI poll.
     * Ignores rssi values outside the bounds of [MIN_RSSI_DELTA, MAX_RSSI_DELTA]
     * mLock must be held when calling this method.
     */
    private void maybeIncrementRssiDeltaCount(int rssi) {
        // Check if this RSSI poll is close enough to a scan result RSSI to log a delta value
        if (mScanResultRssiTimestampMillis >= 0) {
            long timeDelta = mClock.getElapsedSinceBootMillis() - mScanResultRssiTimestampMillis;
            if (timeDelta <= TIMEOUT_RSSI_DELTA_MILLIS) {
                if (rssi >= MIN_RSSI_DELTA && rssi <= MAX_RSSI_DELTA) {
                    int count = mRssiDeltaCounts.get(rssi);
                    mRssiDeltaCounts.put(rssi, count + 1);
                }
            }
            mScanResultRssiTimestampMillis = -1;
        }
    }

    /**
     * Increment occurrence count of link speed.
     * Ignores link speed values that are lower than MIN_LINK_SPEED_MBPS
     * and rssi values outside the bounds of [MIN_RSSI_POLL, MAX_RSSI_POLL]
     */
    @VisibleForTesting
    public void incrementLinkSpeedCount(int linkSpeed, int rssi) {
        if (!(mLinkSpeedCountsLogging
                && linkSpeed >= MIN_LINK_SPEED_MBPS
                && rssi >= MIN_RSSI_POLL
                && rssi <= MAX_RSSI_POLL)) {
            return;
        }
        synchronized (mLock) {
            LinkSpeedCount linkSpeedCount = mLinkSpeedCounts.get(linkSpeed);
            if (linkSpeedCount == null) {
                linkSpeedCount = new LinkSpeedCount();
                linkSpeedCount.linkSpeedMbps = linkSpeed;
                mLinkSpeedCounts.put(linkSpeed, linkSpeedCount);
            }
            linkSpeedCount.count++;
            linkSpeedCount.rssiSumDbm += Math.abs(rssi);
            linkSpeedCount.rssiSumOfSquaresDbmSq += rssi * rssi;
        }
    }

    /**
     * Increment count of Watchdog successes.
     */
    public void incrementNumLastResortWatchdogSuccesses() {
        synchronized (mLock) {
            mWifiLogProto.numLastResortWatchdogSuccesses++;
        }
    }

    /**
     * Increment the count of network connection failures that happened after watchdog has been
     * triggered.
     */
    public void incrementWatchdogTotalConnectionFailureCountAfterTrigger() {
        synchronized (mLock) {
            mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger++;
        }
    }

    /**
     * Sets the time taken for wifi to connect after a watchdog triggers a restart.
     * @param milliseconds
     */
    public void setWatchdogSuccessTimeDurationMs(long ms) {
        synchronized (mLock) {
            mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs = ms;
        }
    }

    /**
     * Increments the count of alerts by alert reason.
     *
     * @param reason The cause of the alert. The reason values are driver-specific.
     */
    private void incrementAlertReasonCount(int reason) {
        if (reason > WifiLoggerHal.WIFI_ALERT_REASON_MAX
                || reason < WifiLoggerHal.WIFI_ALERT_REASON_MIN) {
            reason = WifiLoggerHal.WIFI_ALERT_REASON_RESERVED;
        }
        synchronized (mLock) {
            int alertCount = mWifiAlertReasonCounts.get(reason);
            mWifiAlertReasonCounts.put(reason, alertCount + 1);
        }
    }

    /**
     * Counts all the different types of networks seen in a set of scan results
     */
    public void countScanResults(List<ScanDetail> scanDetails) {
        if (scanDetails == null) {
            return;
        }
        int totalResults = 0;
        int openNetworks = 0;
        int personalNetworks = 0;
        int enterpriseNetworks = 0;
        int hiddenNetworks = 0;
        int hotspot2r1Networks = 0;
        int hotspot2r2Networks = 0;
        for (ScanDetail scanDetail : scanDetails) {
            NetworkDetail networkDetail = scanDetail.getNetworkDetail();
            ScanResult scanResult = scanDetail.getScanResult();
            totalResults++;
            if (networkDetail != null) {
                if (networkDetail.isHiddenBeaconFrame()) {
                    hiddenNetworks++;
                }
                if (networkDetail.getHSRelease() != null) {
                    if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                        hotspot2r1Networks++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                        hotspot2r2Networks++;
                    }
                }
            }
            if (scanResult != null && scanResult.capabilities != null) {
                if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
                    enterpriseNetworks++;
                } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                        || ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                    personalNetworks++;
                } else {
                    openNetworks++;
                }
            }
        }
        synchronized (mLock) {
            mWifiLogProto.numTotalScanResults += totalResults;
            mWifiLogProto.numOpenNetworkScanResults += openNetworks;
            mWifiLogProto.numPersonalNetworkScanResults += personalNetworks;
            mWifiLogProto.numEnterpriseNetworkScanResults += enterpriseNetworks;
            mWifiLogProto.numHiddenNetworkScanResults += hiddenNetworks;
            mWifiLogProto.numHotspot2R1NetworkScanResults += hotspot2r1Networks;
            mWifiLogProto.numHotspot2R2NetworkScanResults += hotspot2r2Networks;
            mWifiLogProto.numScans++;
        }
    }

    private boolean mWifiWins = false; // Based on scores, use wifi instead of mobile data?

    /**
     * Increments occurence of a particular wifi score calculated
     * in WifiScoreReport by current connected network. Scores are bounded
     * within  [MIN_WIFI_SCORE, MAX_WIFI_SCORE] to limit size of SparseArray.
     *
     * Also records events when the current score breaches significant thresholds.
     */
    public void incrementWifiScoreCount(int score) {
        if (score < MIN_WIFI_SCORE || score > MAX_WIFI_SCORE) {
            return;
        }
        synchronized (mLock) {
            int count = mWifiScoreCounts.get(score);
            mWifiScoreCounts.put(score, count + 1);

            boolean wifiWins = mWifiWins;
            if (mWifiWins && score < LOW_WIFI_SCORE) {
                wifiWins = false;
            } else if (!mWifiWins && score > LOW_WIFI_SCORE) {
                wifiWins = true;
            }
            mLastScore = score;
            mLastScoreNoReset = score;
            if (wifiWins != mWifiWins) {
                mWifiWins = wifiWins;
                StaEvent event = new StaEvent();
                event.type = StaEvent.TYPE_SCORE_BREACH;
                addStaEvent(event);
            }
        }
    }

    /**
     * Increments occurence of the results from attempting to start SoftAp.
     * Maps the |result| and WifiManager |failureCode| constant to proto defined SoftApStartResult
     * codes.
     */
    public void incrementSoftApStartResult(boolean result, int failureCode) {
        synchronized (mLock) {
            if (result) {
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY,
                        count + 1);
                return;
            }

            // now increment failure modes - if not explicitly handled, dump into the general
            // error bucket.
            if (failureCode == WifiManager.SAP_START_FAILURE_NO_CHANNEL) {
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL,
                        count + 1);
            } else {
                // failure mode not tracked at this time...  count as a general error for now.
                int count = mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR);
                mSoftApManagerReturnCodeCounts.put(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR,
                        count + 1);
            }
        }
    }

    /**
     * Adds a record indicating the current up state of soft AP
     */
    public void addSoftApUpChangedEvent(boolean isUp, int mode) {
        SoftApConnectedClientsEvent event = new SoftApConnectedClientsEvent();
        event.eventType = isUp ? SoftApConnectedClientsEvent.SOFT_AP_UP :
                SoftApConnectedClientsEvent.SOFT_AP_DOWN;
        event.numConnectedClients = 0;
        addSoftApConnectedClientsEvent(event, mode);
    }

    /**
     * Adds a record for current number of associated stations to soft AP
     */
    public void addSoftApNumAssociatedStationsChangedEvent(int numStations, int mode) {
        SoftApConnectedClientsEvent event = new SoftApConnectedClientsEvent();
        event.eventType = SoftApConnectedClientsEvent.NUM_CLIENTS_CHANGED;
        event.numConnectedClients = numStations;
        addSoftApConnectedClientsEvent(event, mode);
    }

    /**
     * Adds a record to the corresponding event list based on mode param
     */
    private void addSoftApConnectedClientsEvent(SoftApConnectedClientsEvent event, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            if (softApEventList.size() > MAX_NUM_SOFT_AP_EVENTS) {
                return;
            }

            event.timeStampMillis = mClock.getElapsedSinceBootMillis();
            softApEventList.add(event);
        }
    }

    /**
     * Updates current soft AP events with channel info
     */
    public void addSoftApChannelSwitchedEvent(int frequency, int bandwidth, int mode) {
        synchronized (mLock) {
            List<SoftApConnectedClientsEvent> softApEventList;
            switch (mode) {
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    softApEventList = mSoftApEventListTethered;
                    break;
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    softApEventList = mSoftApEventListLocalOnly;
                    break;
                default:
                    return;
            }

            for (int index = softApEventList.size() - 1; index >= 0; index--) {
                SoftApConnectedClientsEvent event = softApEventList.get(index);

                if (event != null && event.eventType == SoftApConnectedClientsEvent.SOFT_AP_UP) {
                    event.channelFrequency = frequency;
                    event.channelBandwidth = bandwidth;
                    break;
                }
            }
        }
    }

    /**
     * Increment number of times the HAL crashed.
     */
    public void incrementNumHalCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numHalCrashes++;
        }
    }

    /**
     * Increment number of times the Wificond crashed.
     */
    public void incrementNumWificondCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numWificondCrashes++;
        }
    }

    /**
     * Increment number of times the supplicant crashed.
     */
    public void incrementNumSupplicantCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numSupplicantCrashes++;
        }
    }

    /**
     * Increment number of times the hostapd crashed.
     */
    public void incrementNumHostapdCrashes() {
        synchronized (mLock) {
            mWifiLogProto.numHostapdCrashes++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in HAL.
     */
    public void incrementNumSetupClientInterfaceFailureDueToHal() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToHal++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in wificond.
     */
    public void incrementNumSetupClientInterfaceFailureDueToWificond() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToWificond++;
        }
    }

    /**
     * Increment number of times the wifi on failed due to an error in supplicant.
     */
    public void incrementNumSetupClientInterfaceFailureDueToSupplicant() {
        synchronized (mLock) {
            mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in HAL.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToHal() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in wificond.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToWificond() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond++;
        }
    }

    /**
     * Increment number of times the SoftAp on failed due to an error in hostapd.
     */
    public void incrementNumSetupSoftApInterfaceFailureDueToHostapd() {
        synchronized (mLock) {
            mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd++;
        }
    }

    /**
     * Increment number of times we got client interface down.
     */
    public void incrementNumClientInterfaceDown() {
        synchronized (mLock) {
            mWifiLogProto.numClientInterfaceDown++;
        }
    }

    /**
     * Increment number of times we got client interface down.
     */
    public void incrementNumSoftApInterfaceDown() {
        synchronized (mLock) {
            mWifiLogProto.numSoftApInterfaceDown++;
        }
    }

    /**
     * Increment number of times Passpoint provider being installed.
     */
    public void incrementNumPasspointProviderInstallation() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderInstallation++;
        }
    }

    /**
     * Increment number of times Passpoint provider is installed successfully.
     */
    public void incrementNumPasspointProviderInstallSuccess() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderInstallSuccess++;
        }
    }

    /**
     * Increment number of times Passpoint provider being uninstalled.
     */
    public void incrementNumPasspointProviderUninstallation() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderUninstallation++;
        }
    }

    /**
     * Increment number of times Passpoint provider is uninstalled successfully.
     */
    public void incrementNumPasspointProviderUninstallSuccess() {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviderUninstallSuccess++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to MCC.
     */
    public void incrementNumRadioModeChangeToMcc() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToMcc++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to SCC.
     */
    public void incrementNumRadioModeChangeToScc() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToScc++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to SBS.
     */
    public void incrementNumRadioModeChangeToSbs() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToSbs++;
        }
    }

    /**
     * Increment number of times we detected a radio mode change to DBS.
     */
    public void incrementNumRadioModeChangeToDbs() {
        synchronized (mLock) {
            mWifiLogProto.numRadioModeChangeToDbs++;
        }
    }

    /**
     * Increment number of times we detected a channel did not satisfy user band preference.
     */
    public void incrementNumSoftApUserBandPreferenceUnsatisfied() {
        synchronized (mLock) {
            mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied++;
        }
    }

    /** Increment the failure count of SAR sensor listener registration */
    public void incrementNumSarSensorRegistrationFailures() {
        synchronized (mLock) {
            mWifiLogProto.numSarSensorRegistrationFailures++;
        }
    }

    /**
     * Increment N-Way network selection decision histograms:
     * Counts the size of various sets of scanDetails within a scan, and increment the occurrence
     * of that size for the associated histogram. There are ten histograms generated for each
     * combination of: {SSID, BSSID} *{Total, Saved, Open, Saved_or_Open, Passpoint}
     * Only performs this count if isFullBand is true, otherwise, increments the partial scan count
     */
    public void incrementAvailableNetworksHistograms(List<ScanDetail> scanDetails,
            boolean isFullBand) {
        synchronized (mLock) {
            if (mWifiConfigManager == null || mWifiNetworkSelector == null
                    || mPasspointManager == null) {
                return;
            }
            if (!isFullBand) {
                mWifiLogProto.partialAllSingleScanListenerResults++;
                return;
            }
            Set<ScanResultMatchInfo> ssids = new HashSet<ScanResultMatchInfo>();
            int bssids = 0;
            Set<ScanResultMatchInfo> openSsids = new HashSet<ScanResultMatchInfo>();
            int openBssids = 0;
            Set<ScanResultMatchInfo> savedSsids = new HashSet<ScanResultMatchInfo>();
            int savedBssids = 0;
            // openOrSavedSsids calculated from union of savedSsids & openSsids
            int openOrSavedBssids = 0;
            Set<PasspointProvider> savedPasspointProviderProfiles =
                    new HashSet<PasspointProvider>();
            int savedPasspointProviderBssids = 0;
            int passpointR1Aps = 0;
            int passpointR2Aps = 0;
            Map<ANQPNetworkKey, Integer> passpointR1UniqueEss = new HashMap<>();
            Map<ANQPNetworkKey, Integer> passpointR2UniqueEss = new HashMap<>();
            int supporting80211mcAps = 0;
            for (ScanDetail scanDetail : scanDetails) {
                NetworkDetail networkDetail = scanDetail.getNetworkDetail();
                ScanResult scanResult = scanDetail.getScanResult();

                // statistics to be collected for ALL APs (irrespective of signal power)
                if (networkDetail.is80211McResponderSupport()) {
                    supporting80211mcAps++;
                }

                ScanResultMatchInfo matchInfo = ScanResultMatchInfo.fromScanResult(scanResult);
                Pair<PasspointProvider, PasspointMatch> providerMatch = null;
                PasspointProvider passpointProvider = null;
                if (networkDetail.isInterworking()) {
                    providerMatch =
                            mPasspointManager.matchProvider(scanResult);
                    passpointProvider = providerMatch != null ? providerMatch.first : null;

                    if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                        passpointR1Aps++;
                    } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                        passpointR2Aps++;
                    }

                    long bssid = 0;
                    boolean validBssid = false;
                    try {
                        bssid = Utils.parseMac(scanResult.BSSID);
                        validBssid = true;
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG,
                                "Invalid BSSID provided in the scan result: " + scanResult.BSSID);
                    }
                    if (validBssid) {
                        ANQPNetworkKey uniqueEss = ANQPNetworkKey.buildKey(scanResult.SSID, bssid,
                                scanResult.hessid, networkDetail.getAnqpDomainID());
                        if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R1) {
                            Integer countObj = passpointR1UniqueEss.get(uniqueEss);
                            int count = countObj == null ? 0 : countObj;
                            passpointR1UniqueEss.put(uniqueEss, count + 1);
                        } else if (networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2) {
                            Integer countObj = passpointR2UniqueEss.get(uniqueEss);
                            int count = countObj == null ? 0 : countObj;
                            passpointR2UniqueEss.put(uniqueEss, count + 1);
                        }
                    }

                }

                if (mWifiNetworkSelector.isSignalTooWeak(scanResult)) {
                    continue;
                }

                // statistics to be collected ONLY for those APs with sufficient signal power

                ssids.add(matchInfo);
                bssids++;
                boolean isOpen = matchInfo.networkType == ScanResultMatchInfo.NETWORK_TYPE_OPEN;
                WifiConfiguration config =
                        mWifiConfigManager.getConfiguredNetworkForScanDetail(scanDetail);
                boolean isSaved = (config != null) && !config.isEphemeral()
                        && !config.isPasspoint();
                boolean isSavedPasspoint = passpointProvider != null;
                if (isOpen) {
                    openSsids.add(matchInfo);
                    openBssids++;
                }
                if (isSaved) {
                    savedSsids.add(matchInfo);
                    savedBssids++;
                }
                if (isOpen || isSaved) {
                    openOrSavedBssids++;
                    // Calculate openOrSavedSsids union later
                }
                if (isSavedPasspoint) {
                    savedPasspointProviderProfiles.add(passpointProvider);
                    savedPasspointProviderBssids++;
                }
            }
            mWifiLogProto.fullBandAllSingleScanListenerResults++;
            incrementTotalScanSsids(mTotalSsidsInScanHistogram, ssids.size());
            incrementTotalScanResults(mTotalBssidsInScanHistogram, bssids);
            incrementSsid(mAvailableOpenSsidsInScanHistogram, openSsids.size());
            incrementBssid(mAvailableOpenBssidsInScanHistogram, openBssids);
            incrementSsid(mAvailableSavedSsidsInScanHistogram, savedSsids.size());
            incrementBssid(mAvailableSavedBssidsInScanHistogram, savedBssids);
            openSsids.addAll(savedSsids); // openSsids = Union(openSsids, savedSsids)
            incrementSsid(mAvailableOpenOrSavedSsidsInScanHistogram, openSsids.size());
            incrementBssid(mAvailableOpenOrSavedBssidsInScanHistogram, openOrSavedBssids);
            incrementSsid(mAvailableSavedPasspointProviderProfilesInScanHistogram,
                    savedPasspointProviderProfiles.size());
            incrementBssid(mAvailableSavedPasspointProviderBssidsInScanHistogram,
                    savedPasspointProviderBssids);
            incrementTotalPasspointAps(mObservedHotspotR1ApInScanHistogram, passpointR1Aps);
            incrementTotalPasspointAps(mObservedHotspotR2ApInScanHistogram, passpointR2Aps);
            incrementTotalUniquePasspointEss(mObservedHotspotR1EssInScanHistogram,
                    passpointR1UniqueEss.size());
            incrementTotalUniquePasspointEss(mObservedHotspotR2EssInScanHistogram,
                    passpointR2UniqueEss.size());
            for (Integer count : passpointR1UniqueEss.values()) {
                incrementPasspointPerUniqueEss(mObservedHotspotR1ApsPerEssInScanHistogram, count);
            }
            for (Integer count : passpointR2UniqueEss.values()) {
                incrementPasspointPerUniqueEss(mObservedHotspotR2ApsPerEssInScanHistogram, count);
            }
            increment80211mcAps(mObserved80211mcApInScanHistogram, supporting80211mcAps);
        }
    }

    /**
     * TODO: (b/72443859) Use notifierTag param to separate metrics for OpenNetworkNotifier and
     * CarrierNetworkNotifier, for this method and all other related metrics.
     */
    /** Increments the occurence of a "Connect to Network" notification. */
    public void incrementConnectToNetworkNotification(String notifierTag, int notificationType) {
        synchronized (mLock) {
            int count = mConnectToNetworkNotificationCount.get(notificationType);
            mConnectToNetworkNotificationCount.put(notificationType, count + 1);
        }
    }

    /** Increments the occurence of an "Connect to Network" notification user action. */
    public void incrementConnectToNetworkNotificationAction(String notifierTag,
            int notificationType, int actionType) {
        synchronized (mLock) {
            int key = notificationType * CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER
                    + actionType;
            int count = mConnectToNetworkNotificationActionCount.get(key);
            mConnectToNetworkNotificationActionCount.put(key, count + 1);
        }
    }

    /**
     * Sets the number of SSIDs blacklisted from recommendation by the open network notification
     * recommender.
     */
    public void setNetworkRecommenderBlacklistSize(String notifierTag, int size) {
        synchronized (mLock) {
            mOpenNetworkRecommenderBlacklistSize = size;
        }
    }

    /** Sets if the available network notification feature is enabled. */
    public void setIsWifiNetworksAvailableNotificationEnabled(String notifierTag, boolean enabled) {
        synchronized (mLock) {
            mIsWifiNetworksAvailableNotificationOn = enabled;
        }
    }

    /** Increments the occurence of connection attempts that were initiated unsuccessfully */
    public void incrementNumNetworkRecommendationUpdates(String notifierTag) {
        synchronized (mLock) {
            mNumOpenNetworkRecommendationUpdates++;
        }
    }

    /** Increments the occurence of connection attempts that were initiated unsuccessfully */
    public void incrementNumNetworkConnectMessageFailedToSend(String notifierTag) {
        synchronized (mLock) {
            mNumOpenNetworkConnectMessageFailedToSend++;
        }
    }

    /** Sets if Connected MAC Randomization feature is enabled */
    public void setIsMacRandomizationOn(boolean enabled) {
        synchronized (mLock) {
            mIsMacRandomizationOn = enabled;
        }
    }

    /** Log firmware alert related metrics */
    public void logFirmwareAlert(int errorCode) {
        incrementAlertReasonCount(errorCode);
        logWifiIsUnusableEvent(WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT, errorCode);
    }

    public static final String PROTO_DUMP_ARG = "wifiMetricsProto";
    public static final String CLEAN_DUMP_ARG = "clean";

    /**
     * Dump all WifiMetrics. Collects some metrics from ConfigStore, Settings and WifiManager
     * at this time.
     *
     * @param fd unused
     * @param pw PrintWriter for writing dump to
     * @param args [wifiMetricsProto [clean]]
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            consolidateScoringParams();
            if (args != null && args.length > 0 && PROTO_DUMP_ARG.equals(args[0])) {
                // Dump serialized WifiLog proto
                consolidateProto();

                byte[] wifiMetricsProto = WifiMetricsProto.WifiLog.toByteArray(mWifiLogProto);
                String metricsProtoDump = Base64.encodeToString(wifiMetricsProto, Base64.DEFAULT);
                if (args.length > 1 && CLEAN_DUMP_ARG.equals(args[1])) {
                    // Output metrics proto bytes (base64) and nothing else
                    pw.print(metricsProtoDump);
                } else {
                    // Tag the start and end of the metrics proto bytes
                    pw.println("WifiMetrics:");
                    pw.println(metricsProtoDump);
                    pw.println("EndWifiMetrics");
                }
                clear();
            } else {
                pw.println("WifiMetrics:");
                pw.println("mConnectionEvents:");
                for (ConnectionEvent event : mConnectionEventList) {
                    String eventLine = event.toString();
                    if (event == mCurrentConnectionEvent) {
                        eventLine += "CURRENTLY OPEN EVENT";
                    }
                    pw.println(eventLine);
                }
                pw.println("mWifiLogProto.numSavedNetworks=" + mWifiLogProto.numSavedNetworks);
                pw.println("mWifiLogProto.numOpenNetworks=" + mWifiLogProto.numOpenNetworks);
                pw.println("mWifiLogProto.numPersonalNetworks="
                        + mWifiLogProto.numPersonalNetworks);
                pw.println("mWifiLogProto.numEnterpriseNetworks="
                        + mWifiLogProto.numEnterpriseNetworks);
                pw.println("mWifiLogProto.numHiddenNetworks=" + mWifiLogProto.numHiddenNetworks);
                pw.println("mWifiLogProto.numPasspointNetworks="
                        + mWifiLogProto.numPasspointNetworks);
                pw.println("mWifiLogProto.isLocationEnabled=" + mWifiLogProto.isLocationEnabled);
                pw.println("mWifiLogProto.isScanningAlwaysEnabled="
                        + mWifiLogProto.isScanningAlwaysEnabled);
                pw.println("mWifiLogProto.numNetworksAddedByUser="
                        + mWifiLogProto.numNetworksAddedByUser);
                pw.println("mWifiLogProto.numNetworksAddedByApps="
                        + mWifiLogProto.numNetworksAddedByApps);
                pw.println("mWifiLogProto.numNonEmptyScanResults="
                        + mWifiLogProto.numNonEmptyScanResults);
                pw.println("mWifiLogProto.numEmptyScanResults="
                        + mWifiLogProto.numEmptyScanResults);
                pw.println("mWifiLogProto.numConnecitvityOneshotScans="
                        + mWifiLogProto.numConnectivityOneshotScans);
                pw.println("mWifiLogProto.numOneshotScans="
                        + mWifiLogProto.numOneshotScans);
                pw.println("mWifiLogProto.numOneshotHasDfsChannelScans="
                        + mWifiLogProto.numOneshotHasDfsChannelScans);
                pw.println("mWifiLogProto.numBackgroundScans="
                        + mWifiLogProto.numBackgroundScans);
                pw.println("mWifiLogProto.numExternalAppOneshotScanRequests="
                        + mWifiLogProto.numExternalAppOneshotScanRequests);
                pw.println("mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled="
                        + mWifiLogProto.numExternalForegroundAppOneshotScanRequestsThrottled);
                pw.println("mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled="
                        + mWifiLogProto.numExternalBackgroundAppOneshotScanRequestsThrottled);

                pw.println("mScanReturnEntries:");
                pw.println("  SCAN_UNKNOWN: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_UNKNOWN));
                pw.println("  SCAN_SUCCESS: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_SUCCESS));
                pw.println("  SCAN_FAILURE_INTERRUPTED: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED));
                pw.println("  SCAN_FAILURE_INVALID_CONFIGURATION: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION));
                pw.println("  FAILURE_WIFI_DISABLED: " + getScanReturnEntry(
                        WifiMetricsProto.WifiLog.FAILURE_WIFI_DISABLED));

                pw.println("mSystemStateEntries: <state><screenOn> : <scansInitiated>");
                pw.println("  WIFI_UNKNOWN       ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN, true));
                pw.println("  WIFI_DISABLED      ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISABLED, true));
                pw.println("  WIFI_DISCONNECTED  ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED, true));
                pw.println("  WIFI_ASSOCIATED    ON: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED, true));
                pw.println("  WIFI_UNKNOWN      OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_UNKNOWN, false));
                pw.println("  WIFI_DISABLED     OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISABLED, false));
                pw.println("  WIFI_DISCONNECTED OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED, false));
                pw.println("  WIFI_ASSOCIATED   OFF: "
                        + getSystemStateCount(WifiMetricsProto.WifiLog.WIFI_ASSOCIATED, false));
                pw.println("mWifiLogProto.numConnectivityWatchdogPnoGood="
                        + mWifiLogProto.numConnectivityWatchdogPnoGood);
                pw.println("mWifiLogProto.numConnectivityWatchdogPnoBad="
                        + mWifiLogProto.numConnectivityWatchdogPnoBad);
                pw.println("mWifiLogProto.numConnectivityWatchdogBackgroundGood="
                        + mWifiLogProto.numConnectivityWatchdogBackgroundGood);
                pw.println("mWifiLogProto.numConnectivityWatchdogBackgroundBad="
                        + mWifiLogProto.numConnectivityWatchdogBackgroundBad);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggers="
                        + mWifiLogProto.numLastResortWatchdogTriggers);
                pw.println("mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadAssociationNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadAuthenticationNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadDhcpNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogBadOtherNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal="
                        + mWifiLogProto.numLastResortWatchdogAvailableNetworksTotal);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadAssociation);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadAuthentication);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadDhcp);
                pw.println("mWifiLogProto.numLastResortWatchdogTriggersWithBadOther="
                        + mWifiLogProto.numLastResortWatchdogTriggersWithBadOther);
                pw.println("mWifiLogProto.numLastResortWatchdogSuccesses="
                        + mWifiLogProto.numLastResortWatchdogSuccesses);
                pw.println("mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger="
                        + mWifiLogProto.watchdogTotalConnectionFailureCountAfterTrigger);
                pw.println("mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs="
                        + mWifiLogProto.watchdogTriggerToConnectionSuccessDurationMs);
                pw.println("mWifiLogProto.recordDurationSec="
                        + ((mClock.getElapsedSinceBootMillis() / 1000) - mRecordStartTimeSec));

                try {
                    JSONObject rssiMap = new JSONObject();
                    for (Map.Entry<Integer, SparseIntArray> entry : mRssiPollCountsMap.entrySet()) {
                        int frequency = entry.getKey();
                        final SparseIntArray histogram = entry.getValue();
                        JSONArray histogramElements = new JSONArray();
                        for (int i = MIN_RSSI_POLL; i <= MAX_RSSI_POLL; i++) {
                            int count = histogram.get(i);
                            if (count == 0) {
                                continue;
                            }
                            JSONObject histogramElement = new JSONObject();
                            histogramElement.put(Integer.toString(i), count);
                            histogramElements.put(histogramElement);
                        }
                        rssiMap.put(Integer.toString(frequency), histogramElements);
                    }
                    pw.println("mWifiLogProto.rssiPollCount: " + rssiMap.toString());
                } catch (JSONException e) {
                    pw.println("JSONException occurred: " + e.getMessage());
                }

                pw.println("mWifiLogProto.rssiPollDeltaCount: Printing counts for ["
                        + MIN_RSSI_DELTA + ", " + MAX_RSSI_DELTA + "]");
                StringBuilder sb = new StringBuilder();
                for (int i = MIN_RSSI_DELTA; i <= MAX_RSSI_DELTA; i++) {
                    sb.append(mRssiDeltaCounts.get(i) + " ");
                }
                pw.println("  " + sb.toString());
                pw.println("mWifiLogProto.linkSpeedCounts: ");
                sb.setLength(0);
                for (int i = 0; i < mLinkSpeedCounts.size(); i++) {
                    LinkSpeedCount linkSpeedCount = mLinkSpeedCounts.valueAt(i);
                    sb.append(linkSpeedCount.linkSpeedMbps).append(":{")
                            .append(linkSpeedCount.count).append(", ")
                            .append(linkSpeedCount.rssiSumDbm).append(", ")
                            .append(linkSpeedCount.rssiSumOfSquaresDbmSq).append("} ");
                }
                if (sb.length() > 0) {
                    pw.println(sb.toString());
                }
                pw.print("mWifiLogProto.alertReasonCounts=");
                sb.setLength(0);
                for (int i = WifiLoggerHal.WIFI_ALERT_REASON_MIN;
                        i <= WifiLoggerHal.WIFI_ALERT_REASON_MAX; i++) {
                    int count = mWifiAlertReasonCounts.get(i);
                    if (count > 0) {
                        sb.append("(" + i + "," + count + "),");
                    }
                }
                if (sb.length() > 1) {
                    sb.setLength(sb.length() - 1);  // strip trailing comma
                    pw.println(sb.toString());
                } else {
                    pw.println("()");
                }
                pw.println("mWifiLogProto.numTotalScanResults="
                        + mWifiLogProto.numTotalScanResults);
                pw.println("mWifiLogProto.numOpenNetworkScanResults="
                        + mWifiLogProto.numOpenNetworkScanResults);
                pw.println("mWifiLogProto.numPersonalNetworkScanResults="
                        + mWifiLogProto.numPersonalNetworkScanResults);
                pw.println("mWifiLogProto.numEnterpriseNetworkScanResults="
                        + mWifiLogProto.numEnterpriseNetworkScanResults);
                pw.println("mWifiLogProto.numHiddenNetworkScanResults="
                        + mWifiLogProto.numHiddenNetworkScanResults);
                pw.println("mWifiLogProto.numHotspot2R1NetworkScanResults="
                        + mWifiLogProto.numHotspot2R1NetworkScanResults);
                pw.println("mWifiLogProto.numHotspot2R2NetworkScanResults="
                        + mWifiLogProto.numHotspot2R2NetworkScanResults);
                pw.println("mWifiLogProto.numScans=" + mWifiLogProto.numScans);
                pw.println("mWifiLogProto.WifiScoreCount: [" + MIN_WIFI_SCORE + ", "
                        + MAX_WIFI_SCORE + "]");
                for (int i = 0; i <= MAX_WIFI_SCORE; i++) {
                    pw.print(mWifiScoreCounts.get(i) + " ");
                }
                pw.println(); // add a line after wifi scores
                pw.println("mWifiLogProto.SoftApManagerReturnCodeCounts:");
                pw.println("  SUCCESS: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_STARTED_SUCCESSFULLY));
                pw.println("  FAILED_GENERAL_ERROR: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_GENERAL_ERROR));
                pw.println("  FAILED_NO_CHANNEL: " + mSoftApManagerReturnCodeCounts.get(
                        WifiMetricsProto.SoftApReturnCodeCount.SOFT_AP_FAILED_NO_CHANNEL));
                pw.print("\n");
                pw.println("mWifiLogProto.numHalCrashes="
                        + mWifiLogProto.numHalCrashes);
                pw.println("mWifiLogProto.numWificondCrashes="
                        + mWifiLogProto.numWificondCrashes);
                pw.println("mWifiLogProto.numSupplicantCrashes="
                        + mWifiLogProto.numSupplicantCrashes);
                pw.println("mWifiLogProto.numHostapdCrashes="
                        + mWifiLogProto.numHostapdCrashes);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToHal="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToHal);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToWificond="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToWificond);
                pw.println("mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant="
                        + mWifiLogProto.numSetupClientInterfaceFailureDueToSupplicant);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToHal);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToWificond);
                pw.println("mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd="
                        + mWifiLogProto.numSetupSoftApInterfaceFailureDueToHostapd);
                pw.println("mWifiLogProto.numSarSensorRegistrationFailures="
                        + mWifiLogProto.numSarSensorRegistrationFailures);
                pw.println("StaEventList:");
                for (StaEventWithTime event : mStaEventList) {
                    pw.println(event);
                }

                pw.println("mWifiLogProto.numPasspointProviders="
                        + mWifiLogProto.numPasspointProviders);
                pw.println("mWifiLogProto.numPasspointProviderInstallation="
                        + mWifiLogProto.numPasspointProviderInstallation);
                pw.println("mWifiLogProto.numPasspointProviderInstallSuccess="
                        + mWifiLogProto.numPasspointProviderInstallSuccess);
                pw.println("mWifiLogProto.numPasspointProviderUninstallation="
                        + mWifiLogProto.numPasspointProviderUninstallation);
                pw.println("mWifiLogProto.numPasspointProviderUninstallSuccess="
                        + mWifiLogProto.numPasspointProviderUninstallSuccess);
                pw.println("mWifiLogProto.numPasspointProvidersSuccessfullyConnected="
                        + mWifiLogProto.numPasspointProvidersSuccessfullyConnected);

                pw.println("mWifiLogProto.installedPasspointProfileType: ");
                for (int i = 0; i < mInstalledPasspointProfileType.size(); i++) {
                    int eapType = mInstalledPasspointProfileType.keyAt(i);
                    pw.println("EAP_METHOD (" + eapType + "): "
                            + mInstalledPasspointProfileType.valueAt(i));
                }

                pw.println("mWifiLogProto.numRadioModeChangeToMcc="
                        + mWifiLogProto.numRadioModeChangeToMcc);
                pw.println("mWifiLogProto.numRadioModeChangeToScc="
                        + mWifiLogProto.numRadioModeChangeToScc);
                pw.println("mWifiLogProto.numRadioModeChangeToSbs="
                        + mWifiLogProto.numRadioModeChangeToSbs);
                pw.println("mWifiLogProto.numRadioModeChangeToDbs="
                        + mWifiLogProto.numRadioModeChangeToDbs);
                pw.println("mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied="
                        + mWifiLogProto.numSoftApUserBandPreferenceUnsatisfied);
                pw.println("mTotalSsidsInScanHistogram:"
                        + mTotalSsidsInScanHistogram.toString());
                pw.println("mTotalBssidsInScanHistogram:"
                        + mTotalBssidsInScanHistogram.toString());
                pw.println("mAvailableOpenSsidsInScanHistogram:"
                        + mAvailableOpenSsidsInScanHistogram.toString());
                pw.println("mAvailableOpenBssidsInScanHistogram:"
                        + mAvailableOpenBssidsInScanHistogram.toString());
                pw.println("mAvailableSavedSsidsInScanHistogram:"
                        + mAvailableSavedSsidsInScanHistogram.toString());
                pw.println("mAvailableSavedBssidsInScanHistogram:"
                        + mAvailableSavedBssidsInScanHistogram.toString());
                pw.println("mAvailableOpenOrSavedSsidsInScanHistogram:"
                        + mAvailableOpenOrSavedSsidsInScanHistogram.toString());
                pw.println("mAvailableOpenOrSavedBssidsInScanHistogram:"
                        + mAvailableOpenOrSavedBssidsInScanHistogram.toString());
                pw.println("mAvailableSavedPasspointProviderProfilesInScanHistogram:"
                        + mAvailableSavedPasspointProviderProfilesInScanHistogram.toString());
                pw.println("mAvailableSavedPasspointProviderBssidsInScanHistogram:"
                        + mAvailableSavedPasspointProviderBssidsInScanHistogram.toString());
                pw.println("mWifiLogProto.partialAllSingleScanListenerResults="
                        + mWifiLogProto.partialAllSingleScanListenerResults);
                pw.println("mWifiLogProto.fullBandAllSingleScanListenerResults="
                        + mWifiLogProto.fullBandAllSingleScanListenerResults);
                pw.println("mWifiAwareMetrics:");
                mWifiAwareMetrics.dump(fd, pw, args);
                pw.println("mRttMetrics:");
                mRttMetrics.dump(fd, pw, args);

                pw.println("mPnoScanMetrics.numPnoScanAttempts="
                        + mPnoScanMetrics.numPnoScanAttempts);
                pw.println("mPnoScanMetrics.numPnoScanFailed="
                        + mPnoScanMetrics.numPnoScanFailed);
                pw.println("mPnoScanMetrics.numPnoScanStartedOverOffload="
                        + mPnoScanMetrics.numPnoScanStartedOverOffload);
                pw.println("mPnoScanMetrics.numPnoScanFailedOverOffload="
                        + mPnoScanMetrics.numPnoScanFailedOverOffload);
                pw.println("mPnoScanMetrics.numPnoFoundNetworkEvents="
                        + mPnoScanMetrics.numPnoFoundNetworkEvents);

                pw.println("mWifiLinkLayerUsageStats.loggingDurationMs="
                        + mWifiLinkLayerUsageStats.loggingDurationMs);
                pw.println("mWifiLinkLayerUsageStats.radioOnTimeMs="
                        + mWifiLinkLayerUsageStats.radioOnTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioTxTimeMs="
                        + mWifiLinkLayerUsageStats.radioTxTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioRxTimeMs="
                        + mWifiLinkLayerUsageStats.radioRxTimeMs);
                pw.println("mWifiLinkLayerUsageStats.radioScanTimeMs="
                        + mWifiLinkLayerUsageStats.radioScanTimeMs);

                pw.println("mWifiLogProto.connectToNetworkNotificationCount="
                        + mConnectToNetworkNotificationCount.toString());
                pw.println("mWifiLogProto.connectToNetworkNotificationActionCount="
                        + mConnectToNetworkNotificationActionCount.toString());
                pw.println("mWifiLogProto.openNetworkRecommenderBlacklistSize="
                        + mOpenNetworkRecommenderBlacklistSize);
                pw.println("mWifiLogProto.isWifiNetworksAvailableNotificationOn="
                        + mIsWifiNetworksAvailableNotificationOn);
                pw.println("mWifiLogProto.numOpenNetworkRecommendationUpdates="
                        + mNumOpenNetworkRecommendationUpdates);
                pw.println("mWifiLogProto.numOpenNetworkConnectMessageFailedToSend="
                        + mNumOpenNetworkConnectMessageFailedToSend);

                pw.println("mWifiLogProto.observedHotspotR1ApInScanHistogram="
                        + mObservedHotspotR1ApInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2ApInScanHistogram="
                        + mObservedHotspotR2ApInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR1EssInScanHistogram="
                        + mObservedHotspotR1EssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2EssInScanHistogram="
                        + mObservedHotspotR2EssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR1ApsPerEssInScanHistogram="
                        + mObservedHotspotR1ApsPerEssInScanHistogram);
                pw.println("mWifiLogProto.observedHotspotR2ApsPerEssInScanHistogram="
                        + mObservedHotspotR2ApsPerEssInScanHistogram);

                pw.println("mWifiLogProto.observed80211mcSupportingApsInScanHistogram"
                        + mObserved80211mcApInScanHistogram);

                pw.println("mSoftApTetheredEvents:");
                for (SoftApConnectedClientsEvent event : mSoftApEventListTethered) {
                    StringBuilder eventLine = new StringBuilder();
                    eventLine.append("event_type=" + event.eventType);
                    eventLine.append(",time_stamp_millis=" + event.timeStampMillis);
                    eventLine.append(",num_connected_clients=" + event.numConnectedClients);
                    eventLine.append(",channel_frequency=" + event.channelFrequency);
                    eventLine.append(",channel_bandwidth=" + event.channelBandwidth);
                    pw.println(eventLine.toString());
                }
                pw.println("mSoftApLocalOnlyEvents:");
                for (SoftApConnectedClientsEvent event : mSoftApEventListLocalOnly) {
                    StringBuilder eventLine = new StringBuilder();
                    eventLine.append("event_type=" + event.eventType);
                    eventLine.append(",time_stamp_millis=" + event.timeStampMillis);
                    eventLine.append(",num_connected_clients=" + event.numConnectedClients);
                    eventLine.append(",channel_frequency=" + event.channelFrequency);
                    eventLine.append(",channel_bandwidth=" + event.channelBandwidth);
                    pw.println(eventLine.toString());
                }

                pw.println("mWpsMetrics.numWpsAttempts="
                        + mWpsMetrics.numWpsAttempts);
                pw.println("mWpsMetrics.numWpsSuccess="
                        + mWpsMetrics.numWpsSuccess);
                pw.println("mWpsMetrics.numWpsStartFailure="
                        + mWpsMetrics.numWpsStartFailure);
                pw.println("mWpsMetrics.numWpsOverlapFailure="
                        + mWpsMetrics.numWpsOverlapFailure);
                pw.println("mWpsMetrics.numWpsTimeoutFailure="
                        + mWpsMetrics.numWpsTimeoutFailure);
                pw.println("mWpsMetrics.numWpsOtherConnectionFailure="
                        + mWpsMetrics.numWpsOtherConnectionFailure);
                pw.println("mWpsMetrics.numWpsSupplicantFailure="
                        + mWpsMetrics.numWpsSupplicantFailure);
                pw.println("mWpsMetrics.numWpsCancellation="
                        + mWpsMetrics.numWpsCancellation);

                mWifiPowerMetrics.dump(pw);
                mWifiWakeMetrics.dump(pw);

                pw.println("mWifiLogProto.isMacRandomizationOn=" + mIsMacRandomizationOn);
                pw.println("mWifiLogProto.scoreExperimentId=" + mWifiLogProto.scoreExperimentId);
                pw.println("mExperimentValues.wifiIsUnusableLoggingEnabled="
                        + mExperimentValues.wifiIsUnusableLoggingEnabled);
                pw.println("mExperimentValues.wifiDataStallMinTxBad="
                        + mExperimentValues.wifiDataStallMinTxBad);
                pw.println("mExperimentValues.wifiDataStallMinTxSuccessWithoutRx="
                        + mExperimentValues.wifiDataStallMinTxSuccessWithoutRx);
                pw.println("mExperimentValues.linkSpeedCountsLoggingEnabled="
                        + mExperimentValues.linkSpeedCountsLoggingEnabled);
                pw.println("WifiIsUnusableEventList: ");
                for (WifiIsUnusableWithTime event : mWifiIsUnusableList) {
                    pw.println(event);
                }
                pw.println("Hardware Version: " + SystemProperties.get("ro.boot.revision", ""));
            }
        }
    }

    /**
     * Update various counts of saved network types
     * @param networks List of WifiConfigurations representing all saved networks, must not be null
     */
    public void updateSavedNetworks(List<WifiConfiguration> networks) {
        synchronized (mLock) {
            mWifiLogProto.numSavedNetworks = networks.size();
            mWifiLogProto.numOpenNetworks = 0;
            mWifiLogProto.numPersonalNetworks = 0;
            mWifiLogProto.numEnterpriseNetworks = 0;
            mWifiLogProto.numNetworksAddedByUser = 0;
            mWifiLogProto.numNetworksAddedByApps = 0;
            mWifiLogProto.numHiddenNetworks = 0;
            mWifiLogProto.numPasspointNetworks = 0;
            for (WifiConfiguration config : networks) {
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                    mWifiLogProto.numOpenNetworks++;
                } else if (config.isEnterprise()) {
                    mWifiLogProto.numEnterpriseNetworks++;
                } else {
                    mWifiLogProto.numPersonalNetworks++;
                }
                if (config.selfAdded) {
                    mWifiLogProto.numNetworksAddedByUser++;
                } else {
                    mWifiLogProto.numNetworksAddedByApps++;
                }
                if (config.hiddenSSID) {
                    mWifiLogProto.numHiddenNetworks++;
                }
                if (config.isPasspoint()) {
                    mWifiLogProto.numPasspointNetworks++;
                }
            }
        }
    }

    /**
     * Update metrics for saved Passpoint profiles.
     *
     * @param numSavedProfiles The number of saved Passpoint profiles
     * @param numConnectedProfiles The number of saved Passpoint profiles that have ever resulted
     *                             in a successful network connection
     */
    public void updateSavedPasspointProfiles(int numSavedProfiles, int numConnectedProfiles) {
        synchronized (mLock) {
            mWifiLogProto.numPasspointProviders = numSavedProfiles;
            mWifiLogProto.numPasspointProvidersSuccessfullyConnected = numConnectedProfiles;
        }
    }

    /**
     * Update number of times for type of saved Passpoint profile.
     *
     * @param providers Passpoint providers installed on the device.
     */
    public void updateSavedPasspointProfilesInfo(
            Map<String, PasspointProvider> providers) {
        int passpointType;
        int eapType;
        PasspointConfiguration config;
        synchronized (mLock) {
            mInstalledPasspointProfileType.clear();
            for (Map.Entry<String, PasspointProvider> entry : providers.entrySet()) {
                config = entry.getValue().getConfig();
                if (config.getCredential().getUserCredential() != null) {
                    eapType = EAPConstants.EAP_TTLS;
                } else if (config.getCredential().getCertCredential() != null) {
                    eapType = EAPConstants.EAP_TLS;
                } else if (config.getCredential().getSimCredential() != null) {
                    eapType = config.getCredential().getSimCredential().getEapType();
                } else {
                    eapType = -1;
                }
                switch (eapType) {
                    case EAPConstants.EAP_TLS:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_TLS;
                        break;
                    case EAPConstants.EAP_TTLS:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_TTLS;
                        break;
                    case EAPConstants.EAP_SIM:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_SIM;
                        break;
                    case EAPConstants.EAP_AKA:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_AKA;
                        break;
                    case EAPConstants.EAP_AKA_PRIME:
                        passpointType =
                                WifiMetricsProto.PasspointProfileTypeCount.TYPE_EAP_AKA_PRIME;
                        break;
                    default:
                        passpointType = WifiMetricsProto.PasspointProfileTypeCount.TYPE_UNKNOWN;

                }
                int count = mInstalledPasspointProfileType.get(passpointType);
                mInstalledPasspointProfileType.put(passpointType, count + 1);
            }
        }
    }

    /**
     * Put all metrics that were being tracked separately into mWifiLogProto
     */
    private void consolidateProto() {
        List<WifiMetricsProto.RssiPollCount> rssis = new ArrayList<>();
        synchronized (mLock) {
            int connectionEventCount = mConnectionEventList.size();
            // Exclude the current active un-ended connection event
            if (mCurrentConnectionEvent != null) {
                connectionEventCount--;
            }
            mWifiLogProto.connectionEvent =
                    new WifiMetricsProto.ConnectionEvent[connectionEventCount];
            for (int i = 0; i < connectionEventCount; i++) {
                mWifiLogProto.connectionEvent[i] = mConnectionEventList.get(i).mConnectionEvent;
            }

            //Convert the SparseIntArray of scanReturnEntry integers into ScanReturnEntry proto list
            mWifiLogProto.scanReturnEntries =
                    new WifiMetricsProto.WifiLog.ScanReturnEntry[mScanReturnEntries.size()];
            for (int i = 0; i < mScanReturnEntries.size(); i++) {
                mWifiLogProto.scanReturnEntries[i] = new WifiMetricsProto.WifiLog.ScanReturnEntry();
                mWifiLogProto.scanReturnEntries[i].scanReturnCode = mScanReturnEntries.keyAt(i);
                mWifiLogProto.scanReturnEntries[i].scanResultsCount = mScanReturnEntries.valueAt(i);
            }

            // Convert the SparseIntArray of systemStateEntry into WifiSystemStateEntry proto list
            // This one is slightly more complex, as the Sparse are indexed with:
            //     key: wifiState * 2 + isScreenOn, value: wifiStateCount
            mWifiLogProto.wifiSystemStateEntries =
                    new WifiMetricsProto.WifiLog
                    .WifiSystemStateEntry[mWifiSystemStateEntries.size()];
            for (int i = 0; i < mWifiSystemStateEntries.size(); i++) {
                mWifiLogProto.wifiSystemStateEntries[i] =
                        new WifiMetricsProto.WifiLog.WifiSystemStateEntry();
                mWifiLogProto.wifiSystemStateEntries[i].wifiState =
                        mWifiSystemStateEntries.keyAt(i) / 2;
                mWifiLogProto.wifiSystemStateEntries[i].wifiStateCount =
                        mWifiSystemStateEntries.valueAt(i);
                mWifiLogProto.wifiSystemStateEntries[i].isScreenOn =
                        (mWifiSystemStateEntries.keyAt(i) % 2) > 0;
            }
            mWifiLogProto.recordDurationSec = (int) ((mClock.getElapsedSinceBootMillis() / 1000)
                    - mRecordStartTimeSec);

            /**
             * Convert the SparseIntArrays of RSSI poll rssi, counts, and frequency to the
             * proto's repeated IntKeyVal array.
             */
            for (Map.Entry<Integer, SparseIntArray> entry : mRssiPollCountsMap.entrySet()) {
                int frequency = entry.getKey();
                SparseIntArray histogram = entry.getValue();
                for (int i = 0; i < histogram.size(); i++) {
                    WifiMetricsProto.RssiPollCount keyVal = new WifiMetricsProto.RssiPollCount();
                    keyVal.rssi = histogram.keyAt(i);
                    keyVal.count = histogram.valueAt(i);
                    keyVal.frequency = frequency;
                    rssis.add(keyVal);
                }
            }
            mWifiLogProto.rssiPollRssiCount = rssis.toArray(mWifiLogProto.rssiPollRssiCount);

            /**
             * Convert the SparseIntArray of RSSI delta rssi's and counts to the proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.rssiPollDeltaCount =
                    new WifiMetricsProto.RssiPollCount[mRssiDeltaCounts.size()];
            for (int i = 0; i < mRssiDeltaCounts.size(); i++) {
                mWifiLogProto.rssiPollDeltaCount[i] = new WifiMetricsProto.RssiPollCount();
                mWifiLogProto.rssiPollDeltaCount[i].rssi = mRssiDeltaCounts.keyAt(i);
                mWifiLogProto.rssiPollDeltaCount[i].count = mRssiDeltaCounts.valueAt(i);
            }

            /**
             * Add LinkSpeedCount objects from mLinkSpeedCounts to proto.
             */
            mWifiLogProto.linkSpeedCounts =
                    new WifiMetricsProto.LinkSpeedCount[mLinkSpeedCounts.size()];
            for (int i = 0; i < mLinkSpeedCounts.size(); i++) {
                mWifiLogProto.linkSpeedCounts[i] = mLinkSpeedCounts.valueAt(i);
            }

            /**
             * Convert the SparseIntArray of alert reasons and counts to the proto's repeated
             * IntKeyVal array.
             */
            mWifiLogProto.alertReasonCount =
                    new WifiMetricsProto.AlertReasonCount[mWifiAlertReasonCounts.size()];
            for (int i = 0; i < mWifiAlertReasonCounts.size(); i++) {
                mWifiLogProto.alertReasonCount[i] = new WifiMetricsProto.AlertReasonCount();
                mWifiLogProto.alertReasonCount[i].reason = mWifiAlertReasonCounts.keyAt(i);
                mWifiLogProto.alertReasonCount[i].count = mWifiAlertReasonCounts.valueAt(i);
            }

            /**
            *  Convert the SparseIntArray of Wifi Score and counts to proto's repeated
            * IntKeyVal array.
            */
            mWifiLogProto.wifiScoreCount =
                    new WifiMetricsProto.WifiScoreCount[mWifiScoreCounts.size()];
            for (int score = 0; score < mWifiScoreCounts.size(); score++) {
                mWifiLogProto.wifiScoreCount[score] = new WifiMetricsProto.WifiScoreCount();
                mWifiLogProto.wifiScoreCount[score].score = mWifiScoreCounts.keyAt(score);
                mWifiLogProto.wifiScoreCount[score].count = mWifiScoreCounts.valueAt(score);
            }

            /**
             * Convert the SparseIntArray of SoftAp Return codes and counts to proto's repeated
             * IntKeyVal array.
             */
            int codeCounts = mSoftApManagerReturnCodeCounts.size();
            mWifiLogProto.softApReturnCode = new WifiMetricsProto.SoftApReturnCodeCount[codeCounts];
            for (int sapCode = 0; sapCode < codeCounts; sapCode++) {
                mWifiLogProto.softApReturnCode[sapCode] =
                        new WifiMetricsProto.SoftApReturnCodeCount();
                mWifiLogProto.softApReturnCode[sapCode].startResult =
                        mSoftApManagerReturnCodeCounts.keyAt(sapCode);
                mWifiLogProto.softApReturnCode[sapCode].count =
                        mSoftApManagerReturnCodeCounts.valueAt(sapCode);
            }

            /**
             * Convert StaEventList to array of StaEvents
             */
            mWifiLogProto.staEventList = new StaEvent[mStaEventList.size()];
            for (int i = 0; i < mStaEventList.size(); i++) {
                mWifiLogProto.staEventList[i] = mStaEventList.get(i).staEvent;
            }
            mWifiLogProto.totalSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mTotalSsidsInScanHistogram);
            mWifiLogProto.totalBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mTotalBssidsInScanHistogram);
            mWifiLogProto.availableOpenSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableOpenSsidsInScanHistogram);
            mWifiLogProto.availableOpenBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableOpenBssidsInScanHistogram);
            mWifiLogProto.availableSavedSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableSavedSsidsInScanHistogram);
            mWifiLogProto.availableSavedBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mAvailableSavedBssidsInScanHistogram);
            mWifiLogProto.availableOpenOrSavedSsidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableOpenOrSavedSsidsInScanHistogram);
            mWifiLogProto.availableOpenOrSavedBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableOpenOrSavedBssidsInScanHistogram);
            mWifiLogProto.availableSavedPasspointProviderProfilesInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableSavedPasspointProviderProfilesInScanHistogram);
            mWifiLogProto.availableSavedPasspointProviderBssidsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                    mAvailableSavedPasspointProviderBssidsInScanHistogram);
            mWifiLogProto.wifiAwareLog = mWifiAwareMetrics.consolidateProto();
            mWifiLogProto.wifiRttLog = mRttMetrics.consolidateProto();

            mWifiLogProto.pnoScanMetrics = mPnoScanMetrics;
            mWifiLogProto.wifiLinkLayerUsageStats = mWifiLinkLayerUsageStats;

            /**
             * Convert the SparseIntArray of "Connect to Network" notification types and counts to
             * proto's repeated IntKeyVal array.
             */
            ConnectToNetworkNotificationAndActionCount[] notificationCountArray =
                    new ConnectToNetworkNotificationAndActionCount[
                            mConnectToNetworkNotificationCount.size()];
            for (int i = 0; i < mConnectToNetworkNotificationCount.size(); i++) {
                ConnectToNetworkNotificationAndActionCount keyVal =
                        new ConnectToNetworkNotificationAndActionCount();
                keyVal.notification = mConnectToNetworkNotificationCount.keyAt(i);
                keyVal.recommender =
                        ConnectToNetworkNotificationAndActionCount.RECOMMENDER_OPEN;
                keyVal.count = mConnectToNetworkNotificationCount.valueAt(i);
                notificationCountArray[i] = keyVal;
            }
            mWifiLogProto.connectToNetworkNotificationCount = notificationCountArray;

            /**
             * Convert the SparseIntArray of "Connect to Network" notification types and counts to
             * proto's repeated IntKeyVal array.
             */
            ConnectToNetworkNotificationAndActionCount[] notificationActionCountArray =
                    new ConnectToNetworkNotificationAndActionCount[
                            mConnectToNetworkNotificationActionCount.size()];
            for (int i = 0; i < mConnectToNetworkNotificationActionCount.size(); i++) {
                ConnectToNetworkNotificationAndActionCount keyVal =
                        new ConnectToNetworkNotificationAndActionCount();
                int key = mConnectToNetworkNotificationActionCount.keyAt(i);
                keyVal.notification = key / CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER;
                keyVal.action = key % CONNECT_TO_NETWORK_NOTIFICATION_ACTION_KEY_MULTIPLIER;
                keyVal.recommender =
                        ConnectToNetworkNotificationAndActionCount.RECOMMENDER_OPEN;
                keyVal.count = mConnectToNetworkNotificationActionCount.valueAt(i);
                notificationActionCountArray[i] = keyVal;
            }

            /**
             * Convert the SparseIntArray of saved Passpoint profile types and counts to proto's
             * repeated IntKeyVal array.
             */
            int counts = mInstalledPasspointProfileType.size();
            mWifiLogProto.installedPasspointProfileType =
                    new WifiMetricsProto.PasspointProfileTypeCount[counts];
            for (int i = 0; i < counts; i++) {
                mWifiLogProto.installedPasspointProfileType[i] =
                        new WifiMetricsProto.PasspointProfileTypeCount();
                mWifiLogProto.installedPasspointProfileType[i].eapMethodType =
                        mInstalledPasspointProfileType.keyAt(i);
                mWifiLogProto.installedPasspointProfileType[i].count =
                        mInstalledPasspointProfileType.valueAt(i);
            }

            mWifiLogProto.connectToNetworkNotificationActionCount = notificationActionCountArray;

            mWifiLogProto.openNetworkRecommenderBlacklistSize =
                    mOpenNetworkRecommenderBlacklistSize;
            mWifiLogProto.isWifiNetworksAvailableNotificationOn =
                    mIsWifiNetworksAvailableNotificationOn;
            mWifiLogProto.numOpenNetworkRecommendationUpdates =
                    mNumOpenNetworkRecommendationUpdates;
            mWifiLogProto.numOpenNetworkConnectMessageFailedToSend =
                    mNumOpenNetworkConnectMessageFailedToSend;

            mWifiLogProto.observedHotspotR1ApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR1ApInScanHistogram);
            mWifiLogProto.observedHotspotR2ApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR2ApInScanHistogram);
            mWifiLogProto.observedHotspotR1EssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR1EssInScanHistogram);
            mWifiLogProto.observedHotspotR2EssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObservedHotspotR2EssInScanHistogram);
            mWifiLogProto.observedHotspotR1ApsPerEssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                            mObservedHotspotR1ApsPerEssInScanHistogram);
            mWifiLogProto.observedHotspotR2ApsPerEssInScanHistogram =
                    makeNumConnectableNetworksBucketArray(
                            mObservedHotspotR2ApsPerEssInScanHistogram);

            mWifiLogProto.observed80211McSupportingApsInScanHistogram =
                    makeNumConnectableNetworksBucketArray(mObserved80211mcApInScanHistogram);

            if (mSoftApEventListTethered.size() > 0) {
                mWifiLogProto.softApConnectedClientsEventsTethered =
                        mSoftApEventListTethered.toArray(
                        mWifiLogProto.softApConnectedClientsEventsTethered);
            }
            if (mSoftApEventListLocalOnly.size() > 0) {
                mWifiLogProto.softApConnectedClientsEventsLocalOnly =
                        mSoftApEventListLocalOnly.toArray(
                        mWifiLogProto.softApConnectedClientsEventsLocalOnly);
            }

            mWifiLogProto.wpsMetrics = mWpsMetrics;
            mWifiLogProto.wifiPowerStats = mWifiPowerMetrics.buildProto();
            mWifiLogProto.wifiRadioUsage = mWifiPowerMetrics.buildWifiRadioUsageProto();
            mWifiLogProto.wifiWakeStats = mWifiWakeMetrics.buildProto();
            mWifiLogProto.isMacRandomizationOn = mIsMacRandomizationOn;
            mWifiLogProto.experimentValues = mExperimentValues;
            mWifiLogProto.wifiIsUnusableEventList =
                    new WifiIsUnusableEvent[mWifiIsUnusableList.size()];
            for (int i = 0; i < mWifiIsUnusableList.size(); i++) {
                mWifiLogProto.wifiIsUnusableEventList[i] = mWifiIsUnusableList.get(i).event;
            }
            mWifiLogProto.hardwareRevision = SystemProperties.get("ro.boot.revision", "");
        }
    }

    /** Sets the scoring experiment id to current value */
    private void consolidateScoringParams() {
        synchronized (mLock) {
            if (mScoringParams != null) {
                int experimentIdentifier = mScoringParams.getExperimentIdentifier();
                if (experimentIdentifier == 0) {
                    mWifiLogProto.scoreExperimentId = "";
                } else {
                    mWifiLogProto.scoreExperimentId = "x" + experimentIdentifier;
                }
            }
        }
    }

    private WifiMetricsProto.NumConnectableNetworksBucket[] makeNumConnectableNetworksBucketArray(
            SparseIntArray sia) {
        WifiMetricsProto.NumConnectableNetworksBucket[] array =
                new WifiMetricsProto.NumConnectableNetworksBucket[sia.size()];
        for (int i = 0; i < sia.size(); i++) {
            WifiMetricsProto.NumConnectableNetworksBucket keyVal =
                    new WifiMetricsProto.NumConnectableNetworksBucket();
            keyVal.numConnectableNetworks = sia.keyAt(i);
            keyVal.count = sia.valueAt(i);
            array[i] = keyVal;
        }
        return array;
    }

    /**
     * Clear all WifiMetrics, except for currentConnectionEvent and Open Network Notification
     * feature enabled state, blacklist size.
     */
    private void clear() {
        synchronized (mLock) {
            loadSettings();
            mConnectionEventList.clear();
            if (mCurrentConnectionEvent != null) {
                mConnectionEventList.add(mCurrentConnectionEvent);
            }
            mScanReturnEntries.clear();
            mWifiSystemStateEntries.clear();
            mRecordStartTimeSec = mClock.getElapsedSinceBootMillis() / 1000;
            mRssiPollCountsMap.clear();
            mRssiDeltaCounts.clear();
            mLinkSpeedCounts.clear();
            mWifiAlertReasonCounts.clear();
            mWifiScoreCounts.clear();
            mWifiLogProto.clear();
            mScanResultRssiTimestampMillis = -1;
            mSoftApManagerReturnCodeCounts.clear();
            mStaEventList.clear();
            mWifiAwareMetrics.clear();
            mRttMetrics.clear();
            mTotalSsidsInScanHistogram.clear();
            mTotalBssidsInScanHistogram.clear();
            mAvailableOpenSsidsInScanHistogram.clear();
            mAvailableOpenBssidsInScanHistogram.clear();
            mAvailableSavedSsidsInScanHistogram.clear();
            mAvailableSavedBssidsInScanHistogram.clear();
            mAvailableOpenOrSavedSsidsInScanHistogram.clear();
            mAvailableOpenOrSavedBssidsInScanHistogram.clear();
            mAvailableSavedPasspointProviderProfilesInScanHistogram.clear();
            mAvailableSavedPasspointProviderBssidsInScanHistogram.clear();
            mPnoScanMetrics.clear();
            mWifiLinkLayerUsageStats.clear();
            mConnectToNetworkNotificationCount.clear();
            mConnectToNetworkNotificationActionCount.clear();
            mNumOpenNetworkRecommendationUpdates = 0;
            mNumOpenNetworkConnectMessageFailedToSend = 0;
            mObservedHotspotR1ApInScanHistogram.clear();
            mObservedHotspotR2ApInScanHistogram.clear();
            mObservedHotspotR1EssInScanHistogram.clear();
            mObservedHotspotR2EssInScanHistogram.clear();
            mObservedHotspotR1ApsPerEssInScanHistogram.clear();
            mObservedHotspotR2ApsPerEssInScanHistogram.clear();
            mSoftApEventListTethered.clear();
            mSoftApEventListLocalOnly.clear();
            mWpsMetrics.clear();
            mWifiWakeMetrics.clear();
            mObserved80211mcApInScanHistogram.clear();
            mWifiIsUnusableList.clear();
            mInstalledPasspointProfileType.clear();
        }
    }

    /**
     *  Set screen state (On/Off)
     */
    public void setScreenState(boolean screenOn) {
        synchronized (mLock) {
            mScreenOn = screenOn;
        }
    }

    /**
     *  Set wifi state (WIFI_UNKNOWN, WIFI_DISABLED, WIFI_DISCONNECTED, WIFI_ASSOCIATED)
     */
    public void setWifiState(int wifiState) {
        synchronized (mLock) {
            mWifiState = wifiState;
            mWifiWins = (wifiState == WifiMetricsProto.WifiLog.WIFI_ASSOCIATED);
        }
    }

    /**
     * Message handler for interesting WifiMonitor messages. Generates StaEvents
     */
    private void processMessage(Message msg) {
        StaEvent event = new StaEvent();
        boolean logEvent = true;
        switch (msg.what) {
            case WifiMonitor.ASSOCIATION_REJECTION_EVENT:
                event.type = StaEvent.TYPE_ASSOCIATION_REJECTION_EVENT;
                event.associationTimedOut = msg.arg1 > 0 ? true : false;
                event.status = msg.arg2;
                break;
            case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                event.type = StaEvent.TYPE_AUTHENTICATION_FAILURE_EVENT;
                switch (msg.arg1) {
                    case WifiManager.ERROR_AUTH_FAILURE_NONE:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_NONE;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_TIMEOUT:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_TIMEOUT;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_WRONG_PSWD;
                        break;
                    case WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE:
                        event.authFailureReason = StaEvent.AUTH_FAILURE_EAP_FAILURE;
                        break;
                    default:
                        break;
                }
                break;
            case WifiMonitor.NETWORK_CONNECTION_EVENT:
                event.type = StaEvent.TYPE_NETWORK_CONNECTION_EVENT;
                break;
            case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                event.type = StaEvent.TYPE_NETWORK_DISCONNECTION_EVENT;
                event.reason = msg.arg2;
                event.localGen = msg.arg1 == 0 ? false : true;
                break;
            case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                logEvent = false;
                StateChangeResult stateChangeResult = (StateChangeResult) msg.obj;
                mSupplicantStateChangeBitmask |= supplicantStateToBit(stateChangeResult.state);
                break;
            case ClientModeImpl.CMD_ASSOCIATED_BSSID:
                event.type = StaEvent.TYPE_CMD_ASSOCIATED_BSSID;
                break;
            case ClientModeImpl.CMD_TARGET_BSSID:
                event.type = StaEvent.TYPE_CMD_TARGET_BSSID;
                break;
            default:
                return;
        }
        if (logEvent) {
            addStaEvent(event);
        }
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     */
    public void logStaEvent(int type) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, null);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param config WifiConfiguration for a framework initiated connection attempt
     */
    public void logStaEvent(int type, WifiConfiguration config) {
        logStaEvent(type, StaEvent.DISCONNECT_UNKNOWN, config);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param frameworkDisconnectReason StaEvent.FrameworkDisconnectReason explaining why framework
     *                                  initiated a FRAMEWORK_DISCONNECT
     */
    public void logStaEvent(int type, int frameworkDisconnectReason) {
        logStaEvent(type, frameworkDisconnectReason, null);
    }
    /**
     * Log a StaEvent from ClientModeImpl. The StaEvent must not be one of the supplicant
     * generated event types, which are logged through 'sendMessage'
     * @param type StaEvent.EventType describing the event
     * @param frameworkDisconnectReason StaEvent.FrameworkDisconnectReason explaining why framework
     *                                  initiated a FRAMEWORK_DISCONNECT
     * @param config WifiConfiguration for a framework initiated connection attempt
     */
    public void logStaEvent(int type, int frameworkDisconnectReason, WifiConfiguration config) {
        switch (type) {
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL:
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST:
            case StaEvent.TYPE_CMD_IP_REACHABILITY_LOST:
            case StaEvent.TYPE_CMD_START_CONNECT:
            case StaEvent.TYPE_CMD_START_ROAM:
            case StaEvent.TYPE_CONNECT_NETWORK:
            case StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK:
            case StaEvent.TYPE_FRAMEWORK_DISCONNECT:
            case StaEvent.TYPE_SCORE_BREACH:
            case StaEvent.TYPE_MAC_CHANGE:
            case StaEvent.TYPE_WIFI_ENABLED:
            case StaEvent.TYPE_WIFI_DISABLED:
                break;
            default:
                Log.e(TAG, "Unknown StaEvent:" + type);
                return;
        }
        StaEvent event = new StaEvent();
        event.type = type;
        if (frameworkDisconnectReason != StaEvent.DISCONNECT_UNKNOWN) {
            event.frameworkDisconnectReason = frameworkDisconnectReason;
        }
        event.configInfo = createConfigInfo(config);
        addStaEvent(event);
    }

    private void addStaEvent(StaEvent staEvent) {
        staEvent.startTimeMillis = mClock.getElapsedSinceBootMillis();
        staEvent.lastRssi = mLastPollRssi;
        staEvent.lastFreq = mLastPollFreq;
        staEvent.lastLinkSpeed = mLastPollLinkSpeed;
        staEvent.supplicantStateChangesBitmask = mSupplicantStateChangeBitmask;
        staEvent.lastScore = mLastScore;
        mSupplicantStateChangeBitmask = 0;
        mLastPollRssi = -127;
        mLastPollFreq = -1;
        mLastPollLinkSpeed = -1;
        mLastScore = -1;
        mStaEventList.add(new StaEventWithTime(staEvent, mClock.getWallClockMillis()));
        // Prune StaEventList if it gets too long
        if (mStaEventList.size() > MAX_STA_EVENTS) mStaEventList.remove();
    }

    private ConfigInfo createConfigInfo(WifiConfiguration config) {
        if (config == null) return null;
        ConfigInfo info = new ConfigInfo();
        info.allowedKeyManagement = bitSetToInt(config.allowedKeyManagement);
        info.allowedProtocols = bitSetToInt(config.allowedProtocols);
        info.allowedAuthAlgorithms = bitSetToInt(config.allowedAuthAlgorithms);
        info.allowedPairwiseCiphers = bitSetToInt(config.allowedPairwiseCiphers);
        info.allowedGroupCiphers = bitSetToInt(config.allowedGroupCiphers);
        info.hiddenSsid = config.hiddenSSID;
        info.isPasspoint = config.isPasspoint();
        info.isEphemeral = config.isEphemeral();
        info.hasEverConnected = config.getNetworkSelectionStatus().getHasEverConnected();
        ScanResult candidate = config.getNetworkSelectionStatus().getCandidate();
        if (candidate != null) {
            info.scanRssi = candidate.level;
            info.scanFreq = candidate.frequency;
        }
        return info;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public WifiAwareMetrics getWifiAwareMetrics() {
        return mWifiAwareMetrics;
    }

    public WifiWakeMetrics getWakeupMetrics() {
        return mWifiWakeMetrics;
    }

    public RttMetrics getRttMetrics() {
        return mRttMetrics;
    }

    // Rather than generate a StaEvent for each SUPPLICANT_STATE_CHANGE, cache these in a bitmask
    // and attach it to the next event which is generated.
    private int mSupplicantStateChangeBitmask = 0;

    /**
     * Converts a SupplicantState value to a single bit, with position defined by
     * {@code StaEvent.SupplicantState}
     */
    public static int supplicantStateToBit(SupplicantState state) {
        switch(state) {
            case DISCONNECTED:
                return 1 << StaEvent.STATE_DISCONNECTED;
            case INTERFACE_DISABLED:
                return 1 << StaEvent.STATE_INTERFACE_DISABLED;
            case INACTIVE:
                return 1 << StaEvent.STATE_INACTIVE;
            case SCANNING:
                return 1 << StaEvent.STATE_SCANNING;
            case AUTHENTICATING:
                return 1 << StaEvent.STATE_AUTHENTICATING;
            case ASSOCIATING:
                return 1 << StaEvent.STATE_ASSOCIATING;
            case ASSOCIATED:
                return 1 << StaEvent.STATE_ASSOCIATED;
            case FOUR_WAY_HANDSHAKE:
                return 1 << StaEvent.STATE_FOUR_WAY_HANDSHAKE;
            case GROUP_HANDSHAKE:
                return 1 << StaEvent.STATE_GROUP_HANDSHAKE;
            case COMPLETED:
                return 1 << StaEvent.STATE_COMPLETED;
            case DORMANT:
                return 1 << StaEvent.STATE_DORMANT;
            case UNINITIALIZED:
                return 1 << StaEvent.STATE_UNINITIALIZED;
            case INVALID:
                return 1 << StaEvent.STATE_INVALID;
            default:
                Log.wtf(TAG, "Got unknown supplicant state: " + state.ordinal());
                return 0;
        }
    }

    private static String supplicantStateChangesBitmaskToString(int mask) {
        StringBuilder sb = new StringBuilder();
        sb.append("supplicantStateChangeEvents: {");
        if ((mask & (1 << StaEvent.STATE_DISCONNECTED)) > 0) sb.append(" DISCONNECTED");
        if ((mask & (1 << StaEvent.STATE_INTERFACE_DISABLED)) > 0) sb.append(" INTERFACE_DISABLED");
        if ((mask & (1 << StaEvent.STATE_INACTIVE)) > 0) sb.append(" INACTIVE");
        if ((mask & (1 << StaEvent.STATE_SCANNING)) > 0) sb.append(" SCANNING");
        if ((mask & (1 << StaEvent.STATE_AUTHENTICATING)) > 0) sb.append(" AUTHENTICATING");
        if ((mask & (1 << StaEvent.STATE_ASSOCIATING)) > 0) sb.append(" ASSOCIATING");
        if ((mask & (1 << StaEvent.STATE_ASSOCIATED)) > 0) sb.append(" ASSOCIATED");
        if ((mask & (1 << StaEvent.STATE_FOUR_WAY_HANDSHAKE)) > 0) sb.append(" FOUR_WAY_HANDSHAKE");
        if ((mask & (1 << StaEvent.STATE_GROUP_HANDSHAKE)) > 0) sb.append(" GROUP_HANDSHAKE");
        if ((mask & (1 << StaEvent.STATE_COMPLETED)) > 0) sb.append(" COMPLETED");
        if ((mask & (1 << StaEvent.STATE_DORMANT)) > 0) sb.append(" DORMANT");
        if ((mask & (1 << StaEvent.STATE_UNINITIALIZED)) > 0) sb.append(" UNINITIALIZED");
        if ((mask & (1 << StaEvent.STATE_INVALID)) > 0) sb.append(" INVALID");
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Returns a human readable string from a Sta Event. Only adds information relevant to the event
     * type.
     */
    public static String staEventToString(StaEvent event) {
        if (event == null) return "<NULL>";
        StringBuilder sb = new StringBuilder();
        switch (event.type) {
            case StaEvent.TYPE_ASSOCIATION_REJECTION_EVENT:
                sb.append("ASSOCIATION_REJECTION_EVENT")
                        .append(" timedOut=").append(event.associationTimedOut)
                        .append(" status=").append(event.status).append(":")
                        .append(ISupplicantStaIfaceCallback.StatusCode.toString(event.status));
                break;
            case StaEvent.TYPE_AUTHENTICATION_FAILURE_EVENT:
                sb.append("AUTHENTICATION_FAILURE_EVENT reason=").append(event.authFailureReason)
                        .append(":").append(authFailureReasonToString(event.authFailureReason));
                break;
            case StaEvent.TYPE_NETWORK_CONNECTION_EVENT:
                sb.append("NETWORK_CONNECTION_EVENT");
                break;
            case StaEvent.TYPE_NETWORK_DISCONNECTION_EVENT:
                sb.append("NETWORK_DISCONNECTION_EVENT")
                        .append(" local_gen=").append(event.localGen)
                        .append(" reason=").append(event.reason).append(":")
                        .append(ISupplicantStaIfaceCallback.ReasonCode.toString(
                                (event.reason >= 0 ? event.reason : -1 * event.reason)));
                break;
            case StaEvent.TYPE_CMD_ASSOCIATED_BSSID:
                sb.append("CMD_ASSOCIATED_BSSID");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_SUCCESSFUL:
                sb.append("CMD_IP_CONFIGURATION_SUCCESSFUL");
                break;
            case StaEvent.TYPE_CMD_IP_CONFIGURATION_LOST:
                sb.append("CMD_IP_CONFIGURATION_LOST");
                break;
            case StaEvent.TYPE_CMD_IP_REACHABILITY_LOST:
                sb.append("CMD_IP_REACHABILITY_LOST");
                break;
            case StaEvent.TYPE_CMD_TARGET_BSSID:
                sb.append("CMD_TARGET_BSSID");
                break;
            case StaEvent.TYPE_CMD_START_CONNECT:
                sb.append("CMD_START_CONNECT");
                break;
            case StaEvent.TYPE_CMD_START_ROAM:
                sb.append("CMD_START_ROAM");
                break;
            case StaEvent.TYPE_CONNECT_NETWORK:
                sb.append("CONNECT_NETWORK");
                break;
            case StaEvent.TYPE_NETWORK_AGENT_VALID_NETWORK:
                sb.append("NETWORK_AGENT_VALID_NETWORK");
                break;
            case StaEvent.TYPE_FRAMEWORK_DISCONNECT:
                sb.append("FRAMEWORK_DISCONNECT")
                        .append(" reason=")
                        .append(frameworkDisconnectReasonToString(event.frameworkDisconnectReason));
                break;
            case StaEvent.TYPE_SCORE_BREACH:
                sb.append("SCORE_BREACH");
                break;
            case StaEvent.TYPE_MAC_CHANGE:
                sb.append("MAC_CHANGE");
                break;
            case StaEvent.TYPE_WIFI_ENABLED:
                sb.append("WIFI_ENABLED");
                break;
            case StaEvent.TYPE_WIFI_DISABLED:
                sb.append("WIFI_DISABLED");
                break;
            default:
                sb.append("UNKNOWN " + event.type + ":");
                break;
        }
        if (event.lastRssi != -127) sb.append(" lastRssi=").append(event.lastRssi);
        if (event.lastFreq != -1) sb.append(" lastFreq=").append(event.lastFreq);
        if (event.lastLinkSpeed != -1) sb.append(" lastLinkSpeed=").append(event.lastLinkSpeed);
        if (event.lastScore != -1) sb.append(" lastScore=").append(event.lastScore);
        if (event.supplicantStateChangesBitmask != 0) {
            sb.append(", ").append(supplicantStateChangesBitmaskToString(
                    event.supplicantStateChangesBitmask));
        }
        if (event.configInfo != null) {
            sb.append(", ").append(configInfoToString(event.configInfo));
        }

        return sb.toString();
    }

    private static String authFailureReasonToString(int authFailureReason) {
        switch (authFailureReason) {
            case StaEvent.AUTH_FAILURE_NONE:
                return "ERROR_AUTH_FAILURE_NONE";
            case StaEvent.AUTH_FAILURE_TIMEOUT:
                return "ERROR_AUTH_FAILURE_TIMEOUT";
            case StaEvent.AUTH_FAILURE_WRONG_PSWD:
                return "ERROR_AUTH_FAILURE_WRONG_PSWD";
            case StaEvent.AUTH_FAILURE_EAP_FAILURE:
                return "ERROR_AUTH_FAILURE_EAP_FAILURE";
            default:
                return "";
        }
    }

    private static String frameworkDisconnectReasonToString(int frameworkDisconnectReason) {
        switch (frameworkDisconnectReason) {
            case StaEvent.DISCONNECT_API:
                return "DISCONNECT_API";
            case StaEvent.DISCONNECT_GENERIC:
                return "DISCONNECT_GENERIC";
            case StaEvent.DISCONNECT_UNWANTED:
                return "DISCONNECT_UNWANTED";
            case StaEvent.DISCONNECT_ROAM_WATCHDOG_TIMER:
                return "DISCONNECT_ROAM_WATCHDOG_TIMER";
            case StaEvent.DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST:
                return "DISCONNECT_P2P_DISCONNECT_WIFI_REQUEST";
            case StaEvent.DISCONNECT_RESET_SIM_NETWORKS:
                return "DISCONNECT_RESET_SIM_NETWORKS";
            default:
                return "DISCONNECT_UNKNOWN=" + frameworkDisconnectReason;
        }
    }

    private static String configInfoToString(ConfigInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("ConfigInfo:")
                .append(" allowed_key_management=").append(info.allowedKeyManagement)
                .append(" allowed_protocols=").append(info.allowedProtocols)
                .append(" allowed_auth_algorithms=").append(info.allowedAuthAlgorithms)
                .append(" allowed_pairwise_ciphers=").append(info.allowedPairwiseCiphers)
                .append(" allowed_group_ciphers=").append(info.allowedGroupCiphers)
                .append(" hidden_ssid=").append(info.hiddenSsid)
                .append(" is_passpoint=").append(info.isPasspoint)
                .append(" is_ephemeral=").append(info.isEphemeral)
                .append(" has_ever_connected=").append(info.hasEverConnected)
                .append(" scan_rssi=").append(info.scanRssi)
                .append(" scan_freq=").append(info.scanFreq);
        return sb.toString();
    }

    public static final int MAX_STA_EVENTS = 768;
    private LinkedList<StaEventWithTime> mStaEventList = new LinkedList<StaEventWithTime>();
    private int mLastPollRssi = -127;
    private int mLastPollLinkSpeed = -1;
    private int mLastPollFreq = -1;
    private int mLastScore = -1;

    /**
     * Converts the first 31 bits of a BitSet to a little endian int
     */
    private static int bitSetToInt(BitSet bits) {
        int value = 0;
        int nBits = bits.length() < 31 ? bits.length() : 31;
        for (int i = 0; i < nBits; i++) {
            value += bits.get(i) ? (1 << i) : 0;
        }
        return value;
    }
    private void incrementSsid(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_CONNECTABLE_SSID_NETWORK_BUCKET));
    }
    private void incrementBssid(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_CONNECTABLE_BSSID_NETWORK_BUCKET));
    }
    private void incrementTotalScanResults(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_SCAN_RESULTS_BUCKET));
    }
    private void incrementTotalScanSsids(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_SCAN_RESULT_SSIDS_BUCKET));
    }
    private void incrementTotalPasspointAps(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_PASSPOINT_APS_BUCKET));
    }
    private void incrementTotalUniquePasspointEss(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_PASSPOINT_UNIQUE_ESS_BUCKET));
    }
    private void incrementPasspointPerUniqueEss(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_PASSPOINT_APS_PER_UNIQUE_ESS_BUCKET));
    }
    private void increment80211mcAps(SparseIntArray sia, int element) {
        increment(sia, Math.min(element, MAX_TOTAL_80211MC_APS_BUCKET));
    }
    private void increment(SparseIntArray sia, int element) {
        int count = sia.get(element);
        sia.put(element, count + 1);
    }

    private static class StaEventWithTime {
        public StaEvent staEvent;
        public long wallClockMillis;

        StaEventWithTime(StaEvent event, long wallClockMillis) {
            staEvent = event;
            this.wallClockMillis = wallClockMillis;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(wallClockMillis);
            if (wallClockMillis != 0) {
                sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            } else {
                sb.append("                  ");
            }
            sb.append(" ").append(staEventToString(staEvent));
            return sb.toString();
        }
    }

    private LinkedList<WifiIsUnusableWithTime> mWifiIsUnusableList =
            new LinkedList<WifiIsUnusableWithTime>();
    private long mTxScucessDelta = 0;
    private long mTxRetriesDelta = 0;
    private long mTxBadDelta = 0;
    private long mRxSuccessDelta = 0;
    private long mLlStatsUpdateTimeDelta = 0;
    private long mLlStatsLastUpdateTime = 0;
    private int mLastScoreNoReset = -1;
    private long mLastDataStallTime = Long.MIN_VALUE;

    private static class WifiIsUnusableWithTime {
        public WifiIsUnusableEvent event;
        public long wallClockMillis;

        WifiIsUnusableWithTime(WifiIsUnusableEvent event, long wallClockMillis) {
            this.event = event;
            this.wallClockMillis = wallClockMillis;
        }

        public String toString() {
            if (event == null) return "<NULL>";
            StringBuilder sb = new StringBuilder();
            if (wallClockMillis != 0) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(wallClockMillis);
                sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
            } else {
                sb.append("                  ");
            }
            sb.append(" ");

            switch(event.type) {
                case WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX:
                    sb.append("DATA_STALL_BAD_TX");
                    break;
                case WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX:
                    sb.append("DATA_STALL_TX_WITHOUT_RX");
                    break;
                case WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH:
                    sb.append("DATA_STALL_BOTH");
                    break;
                case WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT:
                    sb.append("FIRMWARE_ALERT");
                    break;
                default:
                    sb.append("UNKNOWN " + event.type);
                    break;
            }

            sb.append(" lastScore=").append(event.lastScore);
            sb.append(" txSuccessDelta=").append(event.txSuccessDelta);
            sb.append(" txRetriesDelta=").append(event.txRetriesDelta);
            sb.append(" txBadDelta=").append(event.txBadDelta);
            sb.append(" rxSuccessDelta=").append(event.rxSuccessDelta);
            sb.append(" packetUpdateTimeDelta=").append(event.packetUpdateTimeDelta)
                    .append("ms");
            if (event.firmwareAlertCode != -1) {
                sb.append(" firmwareAlertCode=").append(event.firmwareAlertCode);
            }
            return sb.toString();
        }
    }

    /**
     * Update the difference between the last two WifiLinkLayerStats for WifiIsUnusableEvent
     */
    public void updateWifiIsUnusableLinkLayerStats(long txSuccessDelta, long txRetriesDelta,
            long txBadDelta, long rxSuccessDelta, long updateTimeDelta) {
        mTxScucessDelta = txSuccessDelta;
        mTxRetriesDelta = txRetriesDelta;
        mTxBadDelta = txBadDelta;
        mRxSuccessDelta = rxSuccessDelta;
        mLlStatsUpdateTimeDelta = updateTimeDelta;
        mLlStatsLastUpdateTime = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Clear the saved difference between the last two WifiLinkLayerStats
     */
    public void resetWifiIsUnusableLinkLayerStats() {
        mTxScucessDelta = 0;
        mTxRetriesDelta = 0;
        mTxBadDelta = 0;
        mRxSuccessDelta = 0;
        mLlStatsUpdateTimeDelta = 0;
        mLlStatsLastUpdateTime = 0;
        mLastDataStallTime = Long.MIN_VALUE;
    }

    /**
     * Log a WifiIsUnusableEvent
     * @param triggerType WifiIsUnusableEvent.type describing the event
     */
    public void logWifiIsUnusableEvent(int triggerType) {
        logWifiIsUnusableEvent(triggerType, -1);
    }

    /**
     * Log a WifiIsUnusableEvent
     * @param triggerType WifiIsUnusableEvent.type describing the event
     * @param firmwareAlertCode WifiIsUnusableEvent.firmwareAlertCode for firmware alert code
     */
    public void logWifiIsUnusableEvent(int triggerType, int firmwareAlertCode) {
        if (!mUnusableEventLogging) {
            return;
        }

        long currentBootTime = mClock.getElapsedSinceBootMillis();
        switch (triggerType) {
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX:
            case WifiIsUnusableEvent.TYPE_DATA_STALL_TX_WITHOUT_RX:
            case WifiIsUnusableEvent.TYPE_DATA_STALL_BOTH:
                // Have a time-based throttle for generating WifiIsUnusableEvent from data stalls
                if (currentBootTime < mLastDataStallTime + MIN_DATA_STALL_WAIT_MS) {
                    return;
                }
                mLastDataStallTime = currentBootTime;
                break;
            case WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT:
                break;
            default:
                Log.e(TAG, "Unknown WifiIsUnusableEvent: " + triggerType);
                return;
        }

        WifiIsUnusableEvent event = new WifiIsUnusableEvent();
        event.type = triggerType;
        if (triggerType == WifiIsUnusableEvent.TYPE_FIRMWARE_ALERT) {
            event.firmwareAlertCode = firmwareAlertCode;
        }
        event.startTimeMillis = currentBootTime;
        event.lastScore = mLastScoreNoReset;
        event.txSuccessDelta = mTxScucessDelta;
        event.txRetriesDelta = mTxRetriesDelta;
        event.txBadDelta = mTxBadDelta;
        event.rxSuccessDelta = mRxSuccessDelta;
        event.packetUpdateTimeDelta = mLlStatsUpdateTimeDelta;
        event.lastLinkLayerStatsUpdateTime = mLlStatsLastUpdateTime;

        mWifiIsUnusableList.add(new WifiIsUnusableWithTime(event, mClock.getWallClockMillis()));
        if (mWifiIsUnusableList.size() > MAX_UNUSABLE_EVENTS) {
            mWifiIsUnusableList.removeFirst();
        }
    }

    /**
     * Sets whether or not WifiIsUnusableEvent is logged in metrics
     */
    @VisibleForTesting
    public void setWifiIsUnusableLoggingEnabled(boolean enabled) {
        synchronized (mLock) {
            mExperimentValues.wifiIsUnusableLoggingEnabled = enabled;
        }
    }

    /**
     * Sets whether or not LinkSpeedCounts is logged in metrics
     */
    @VisibleForTesting
    public void setLinkSpeedCountsLoggingEnabled(boolean enabled) {
        synchronized (mLock) {
            mExperimentValues.linkSpeedCountsLoggingEnabled = enabled;
        }
    }

    /**
     * Sets the minimum number of txBad to trigger a data stall
     */
    public void setWifiDataStallMinTxBad(int minTxBad) {
        synchronized (mLock) {
            mExperimentValues.wifiDataStallMinTxBad = minTxBad;
        }
    }

    /**
     * Sets the minimum number of txSuccess to trigger a data stall
     * when rxSuccess is 0
     */
    public void setWifiDataStallMinRxWithoutTx(int minTxSuccessWithoutRx) {
        synchronized (mLock) {
            mExperimentValues.wifiDataStallMinTxSuccessWithoutRx = minTxSuccessWithoutRx;
        }
    }
}
