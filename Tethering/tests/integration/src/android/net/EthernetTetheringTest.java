/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringTester.isExpectedIcmpv6Packet;
import static android.net.TetheringTester.isExpectedUdpPacket;
import static android.system.OsConstants.IPPROTO_IP;
import static android.system.OsConstants.IPPROTO_IPV6;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.net.module.util.ConnectivityUtils.isIPv6ULA;
import static com.android.net.module.util.HexDump.dumpHexString;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.testutils.DeviceInfoUtils.KVersion;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.net.EthernetManager.TetheredInterfaceCallback;
import android.net.EthernetManager.TetheredInterfaceRequest;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.TetheringManager.TetheringRequest;
import android.net.TetheringTester.TetheredDevice;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.VintfRuntimeInfo;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.PacketBuilder;
import com.android.net.module.util.Struct;
import com.android.net.module.util.bpf.Tether4Key;
import com.android.net.module.util.bpf.Tether4Value;
import com.android.net.module.util.bpf.TetherStatsKey;
import com.android.net.module.util.bpf.TetherStatsValue;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DeviceInfoUtils;
import com.android.testutils.DumpTestUtils;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class EthernetTetheringTest {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final String TAG = EthernetTetheringTest.class.getSimpleName();
    private static final int TIMEOUT_MS = 5000;
    private static final int TETHER_REACHABILITY_ATTEMPTS = 20;
    private static final int DUMP_POLLING_MAX_RETRY = 100;
    private static final int DUMP_POLLING_INTERVAL_MS = 50;
    // Kernel treats a confirmed UDP connection which active after two seconds as stream mode.
    // See upstream commit b7b1d02fc43925a4d569ec221715db2dfa1ce4f5.
    private static final int UDP_STREAM_TS_MS = 2000;
    // Per RX UDP packet size: iphdr (20) + udphdr (8) + payload (2) = 30 bytes.
    private static final int RX_UDP_PACKET_SIZE = 30;
    private static final int RX_UDP_PACKET_COUNT = 456;
    // Per TX UDP packet size: ethhdr (14) + iphdr (20) + udphdr (8) + payload (2) = 44 bytes.
    private static final int TX_UDP_PACKET_SIZE = 44;
    private static final int TX_UDP_PACKET_COUNT = 123;
    private static final long WAIT_RA_TIMEOUT_MS = 2000;

    private static final LinkAddress TEST_IP4_ADDR = new LinkAddress("10.0.0.1/8");
    private static final LinkAddress TEST_IP6_ADDR = new LinkAddress("2001:db8:1::101/64");
    private static final InetAddress TEST_IP4_DNS = parseNumericAddress("8.8.8.8");
    private static final InetAddress TEST_IP6_DNS = parseNumericAddress("2001:db8:1::888");
    private static final IpPrefix TEST_NAT64PREFIX = new IpPrefix("64:ff9b::/96");
    private static final Inet6Address REMOTE_NAT64_ADDR =
            (Inet6Address) parseNumericAddress("64:ff9b::808:808");
    private static final ByteBuffer TEST_REACHABILITY_PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x55, (byte) 0xaa });

    private static final String DUMPSYS_TETHERING_RAWMAP_ARG = "bpfRawMap";
    private static final String DUMPSYS_RAWMAP_ARG_STATS = "--stats";
    private static final String DUMPSYS_RAWMAP_ARG_UPSTREAM4 = "--upstream4";
    private static final String BASE64_DELIMITER = ",";
    private static final String LINE_DELIMITER = "\\n";

    // version=6, traffic class=0x0, flowlabel=0x0;
    private static final int VERSION_TRAFFICCLASS_FLOWLABEL = 0x60000000;
    private static final short HOP_LIMIT = 0x40;

    private final Context mContext = InstrumentationRegistry.getContext();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final TetheringManager mTm = mContext.getSystemService(TetheringManager.class);

    private TestNetworkInterface mDownstreamIface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mDownstreamReader;
    private TapPacketReader mUpstreamReader;

    private TetheredInterfaceRequester mTetheredInterfaceRequester;
    private MyTetheringEventCallback mTetheringEventCallback;

    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private boolean mRunTests;

    private TestNetworkTracker mUpstreamTracker;

    @Before
    public void setUp() throws Exception {
        // Needed to create a TestNetworkInterface, to call requestTetheredInterface, and to receive
        // tethered client callbacks. The restricted networks permission is needed to ensure that
        // EthernetManager#isAvailable will correctly return true on devices where Ethernet is
        // marked restricted, like cuttlefish. The dump permission is needed to verify bpf related
        // functions via dumpsys output.
        mUiAutomation.adoptShellPermissionIdentity(
                MANAGE_TEST_NETWORKS, NETWORK_SETTINGS, TETHER_PRIVILEGED, ACCESS_NETWORK_STATE,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS, DUMP);
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mRunTests = isEthernetTetheringSupported();
        assumeTrue(mRunTests);

        mTetheredInterfaceRequester = new TetheredInterfaceRequester(mHandler, mEm);
    }

    private void cleanUp() throws Exception {
        mTm.setPreferTestNetworks(false);

        if (mUpstreamTracker != null) {
            mUpstreamTracker.teardown();
            mUpstreamTracker = null;
        }
        if (mUpstreamReader != null) {
            TapPacketReader reader = mUpstreamReader;
            mHandler.post(() -> reader.stop());
            mUpstreamReader = null;
        }

        mTm.stopTethering(TETHERING_ETHERNET);
        if (mTetheringEventCallback != null) {
            mTetheringEventCallback.awaitInterfaceUntethered();
            mTetheringEventCallback.unregister();
            mTetheringEventCallback = null;
        }
        if (mDownstreamReader != null) {
            TapPacketReader reader = mDownstreamReader;
            mHandler.post(() -> reader.stop());
            mDownstreamReader = null;
        }
        mTetheredInterfaceRequester.release();
        mEm.setIncludeTestInterfaces(false);
        maybeDeleteTestInterface();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (mRunTests) cleanUp();
        } finally {
            mHandlerThread.quitSafely();
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testVirtualEthernetAlreadyExists() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(mEm.isAvailable());

        mDownstreamIface = createTestInterface();
        // This must be done now because as soon as setIncludeTestInterfaces(true) is called, the
        // interface will be placed in client mode, which will delete the link-local address.
        // At that point NetworkInterface.getByName() will cease to work on the interface, because
        // starting in R NetworkInterface can no longer see interfaces without IP addresses.
        int mtu = getMTU(mDownstreamIface);

        Log.d(TAG, "Including test interfaces");
        mEm.setIncludeTestInterfaces(true);

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        checkVirtualEthernet(mDownstreamIface, mtu);
    }

    @Test
    public void testVirtualEthernet() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(mEm.isAvailable());

        CompletableFuture<String> futureIface = mTetheredInterfaceRequester.requestInterface();

        mEm.setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = futureIface.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        checkVirtualEthernet(mDownstreamIface, getMTU(mDownstreamIface));
    }

    @Test
    public void testStaticIpv4() throws Exception {
        assumeFalse(mEm.isAvailable());

        mEm.setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        assertInvalidStaticIpv4Request(iface, null, null);
        assertInvalidStaticIpv4Request(iface, "2001:db8::1/64", "2001:db8:2::/64");
        assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", "2001:db8:2::/28");
        assertInvalidStaticIpv4Request(iface, "2001:db8:2::/28", "192.0.2.2/28");
        assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", null);
        assertInvalidStaticIpv4Request(iface, null, "192.0.2.2/28");
        assertInvalidStaticIpv4Request(iface, "192.0.2.3/27", "192.0.2.2/28");

        final String localAddr = "192.0.2.3/28";
        final String clientAddr = "192.0.2.2/28";
        mTetheringEventCallback = enableEthernetTethering(iface,
                requestWithStaticIpv4(localAddr, clientAddr), null /* any upstream */);

        mTetheringEventCallback.awaitInterfaceTethered();
        assertInterfaceHasIpAddress(iface, localAddr);

        byte[] client1 = MacAddress.fromString("1:2:3:4:5:6").toByteArray();
        byte[] client2 = MacAddress.fromString("a:b:c:d:e:f").toByteArray();

        FileDescriptor fd = mDownstreamIface.getFileDescriptor().getFileDescriptor();
        mDownstreamReader = makePacketReader(fd, getMTU(mDownstreamIface));
        TetheringTester tester = new TetheringTester(mDownstreamReader);
        DhcpResults dhcpResults = tester.runDhcp(client1);
        assertEquals(new LinkAddress(clientAddr), dhcpResults.ipAddress);

        try {
            tester.runDhcp(client2);
            fail("Only one client should get an IP address");
        } catch (TimeoutException expected) { }

    }

    private static void waitForRouterAdvertisement(TapPacketReader reader, String iface,
            long timeoutMs) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            byte[] pkt = reader.popPacket(timeoutMs);
            if (isExpectedIcmpv6Packet(pkt, true /* hasEth */, ICMPV6_ROUTER_ADVERTISEMENT)) return;

            timeoutMs = deadline - SystemClock.uptimeMillis();
        } while (timeoutMs > 0);
        fail("Did not receive router advertisement on " + iface + " after "
                +  timeoutMs + "ms idle");
    }

    private static void expectLocalOnlyAddresses(String iface) throws Exception {
        final List<InterfaceAddress> interfaceAddresses =
                NetworkInterface.getByName(iface).getInterfaceAddresses();

        boolean foundIpv6Ula = false;
        for (InterfaceAddress ia : interfaceAddresses) {
            final InetAddress addr = ia.getAddress();
            if (isIPv6ULA(addr)) {
                foundIpv6Ula = true;
            }
            final int prefixlen = ia.getNetworkPrefixLength();
            final LinkAddress la = new LinkAddress(addr, prefixlen);
            if (la.isIpv6() && la.isGlobalPreferred()) {
                fail("Found global IPv6 address on local-only interface: " + interfaceAddresses);
            }
        }

        assertTrue("Did not find IPv6 ULA on local-only interface " + iface,
                foundIpv6Ula);
    }

    @Test
    public void testLocalOnlyTethering() throws Exception {
        assumeFalse(mEm.isAvailable());

        mEm.setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL).build();
        mTetheringEventCallback = enableEthernetTethering(iface, request,
                null /* any upstream */);
        mTetheringEventCallback.awaitInterfaceLocalOnly();

        // makePacketReader only works after tethering is started, because until then the interface
        // does not have an IP address, and unprivileged apps cannot see interfaces without IP
        // addresses. This shouldn't be flaky because the TAP interface will buffer all packets even
        // before the reader is started.
        mDownstreamReader = makePacketReader(mDownstreamIface);

        waitForRouterAdvertisement(mDownstreamReader, iface, WAIT_RA_TIMEOUT_MS);
        expectLocalOnlyAddresses(iface);
    }

    private boolean isAdbOverNetwork() {
        // If adb TCP port opened, this test may running by adb over network.
        return (SystemProperties.getInt("persist.adb.tcp.port", -1) > -1)
                || (SystemProperties.getInt("service.adb.tcp.port", -1) > -1);
    }

    @Test
    public void testPhysicalEthernet() throws Exception {
        assumeTrue(mEm.isAvailable());
        // Do not run this test if adb is over network and ethernet is connected.
        // It is likely the adb run over ethernet, the adb would break when ethernet is switching
        // from client mode to server mode. See b/160389275.
        assumeFalse(isAdbOverNetwork());

        // Get an interface to use.
        final String iface = mTetheredInterfaceRequester.getInterface();

        // Enable Ethernet tethering and check that it starts.
        mTetheringEventCallback = enableEthernetTethering(iface, null /* any upstream */);

        // There is nothing more we can do on a physical interface without connecting an actual
        // client, which is not possible in this test.
    }

    private boolean isEthernetTetheringSupported() throws Exception {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        final TetheringEventCallback callback = new TetheringEventCallback() {
            @Override
            public void onSupportedTetheringTypes(Set<Integer> supportedTypes) {
                future.complete(supportedTypes.contains(TETHERING_ETHERNET));
            }
        };

        try {
            mTm.registerTetheringEventCallback(mHandler::post, callback);
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            mTm.unregisterTetheringEventCallback(callback);
        }
    }

    private static final class MyTetheringEventCallback implements TetheringEventCallback {
        private final TetheringManager mTm;
        private final CountDownLatch mTetheringStartedLatch = new CountDownLatch(1);
        private final CountDownLatch mTetheringStoppedLatch = new CountDownLatch(1);
        private final CountDownLatch mLocalOnlyStartedLatch = new CountDownLatch(1);
        private final CountDownLatch mLocalOnlyStoppedLatch = new CountDownLatch(1);
        private final CountDownLatch mClientConnectedLatch = new CountDownLatch(1);
        private final CountDownLatch mUpstreamLatch = new CountDownLatch(1);
        private final TetheringInterface mIface;
        private final Network mExpectedUpstream;

        private boolean mAcceptAnyUpstream = false;

        private volatile boolean mInterfaceWasTethered = false;
        private volatile boolean mInterfaceWasLocalOnly = false;
        private volatile boolean mUnregistered = false;
        private volatile Collection<TetheredClient> mClients = null;
        private volatile Network mUpstream = null;

        MyTetheringEventCallback(TetheringManager tm, String iface) {
            this(tm, iface, null);
            mAcceptAnyUpstream = true;
        }

        MyTetheringEventCallback(TetheringManager tm, String iface, Network expectedUpstream) {
            mTm = tm;
            mIface = new TetheringInterface(TETHERING_ETHERNET, iface);
            mExpectedUpstream = expectedUpstream;
        }

        public void unregister() {
            mTm.unregisterTetheringEventCallback(this);
            mUnregistered = true;
        }
        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            fail("Should only call callback that takes a Set<TetheringInterface>");
        }

        @Override
        public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            if (!mInterfaceWasTethered && interfaces.contains(mIface)) {
                // This interface is being tethered for the first time.
                Log.d(TAG, "Tethering started: " + interfaces);
                mInterfaceWasTethered = true;
                mTetheringStartedLatch.countDown();
            } else if (mInterfaceWasTethered && !interfaces.contains(mIface)) {
                Log.d(TAG, "Tethering stopped: " + interfaces);
                mTetheringStoppedLatch.countDown();
            }
        }

        @Override
        public void onLocalOnlyInterfacesChanged(List<String> interfaces) {
            fail("Should only call callback that takes a Set<TetheringInterface>");
        }

        @Override
        public void onLocalOnlyInterfacesChanged(Set<TetheringInterface> interfaces) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            if (!mInterfaceWasLocalOnly && interfaces.contains(mIface)) {
                // This interface is being put into local-only mode for the first time.
                Log.d(TAG, "Local-only started: " + interfaces);
                mInterfaceWasLocalOnly = true;
                mLocalOnlyStartedLatch.countDown();
            } else if (mInterfaceWasLocalOnly && !interfaces.contains(mIface)) {
                Log.d(TAG, "Local-only stopped: " + interfaces);
                mLocalOnlyStoppedLatch.countDown();
            }
        }

        public void awaitInterfaceTethered() throws Exception {
            assertTrue("Ethernet not tethered after " + TIMEOUT_MS + "ms",
                    mTetheringStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        public void awaitInterfaceLocalOnly() throws Exception {
            assertTrue("Ethernet not local-only after " + TIMEOUT_MS + "ms",
                    mLocalOnlyStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        public void awaitInterfaceUntethered() throws Exception {
            // Don't block teardown if the interface was never tethered.
            // This is racy because the interface might become tethered right after this check, but
            // that can only happen in tearDown if startTethering timed out, which likely means
            // the test has already failed.
            if (!mInterfaceWasTethered && !mInterfaceWasLocalOnly) return;

            if (mInterfaceWasTethered) {
                assertTrue(mIface + " not untethered after " + TIMEOUT_MS + "ms",
                        mTetheringStoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } else if (mInterfaceWasLocalOnly) {
                assertTrue(mIface + " not untethered after " + TIMEOUT_MS + "ms",
                        mLocalOnlyStoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } else {
                fail(mIface + " cannot be both tethered and local-only. Update this test class.");
            }
        }

        @Override
        public void onError(String ifName, int error) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            fail("TetheringEventCallback got error:" + error + " on iface " + ifName);
        }

        @Override
        public void onClientsChanged(Collection<TetheredClient> clients) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            Log.d(TAG, "Got clients changed: " + clients);
            mClients = clients;
            if (clients.size() > 0) {
                mClientConnectedLatch.countDown();
            }
        }

        public Collection<TetheredClient> awaitClientConnected() throws Exception {
            assertTrue("Did not receive client connected callback after " + TIMEOUT_MS + "ms",
                    mClientConnectedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return mClients;
        }

        @Override
        public void onUpstreamChanged(Network network) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            Log.d(TAG, "Got upstream changed: " + network);
            mUpstream = network;
            if (mAcceptAnyUpstream || Objects.equals(mUpstream, mExpectedUpstream)) {
                mUpstreamLatch.countDown();
            }
        }

        public Network awaitUpstreamChanged() throws Exception {
            if (!mUpstreamLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("Did not receive upstream " + (mAcceptAnyUpstream ? "any" : mExpectedUpstream)
                        + " callback after " + TIMEOUT_MS + "ms");
            }
            return mUpstream;
        }
    }

    private MyTetheringEventCallback enableEthernetTethering(String iface,
            TetheringRequest request, Network expectedUpstream) throws Exception {
        // Enable ethernet tethering with null expectedUpstream means the test accept any upstream
        // after etherent tethering started.
        final MyTetheringEventCallback callback;
        if (expectedUpstream != null) {
            callback = new MyTetheringEventCallback(mTm, iface, expectedUpstream);
        } else {
            callback = new MyTetheringEventCallback(mTm, iface);
        }
        mTm.registerTetheringEventCallback(mHandler::post, callback);

        StartTetheringCallback startTetheringCallback = new StartTetheringCallback() {
            @Override
            public void onTetheringFailed(int resultCode) {
                fail("Unexpectedly got onTetheringFailed");
            }
        };
        Log.d(TAG, "Starting Ethernet tethering");
        mTm.startTethering(request, mHandler::post /* executor */,  startTetheringCallback);

        final int connectivityType = request.getConnectivityScope();
        switch (connectivityType) {
            case CONNECTIVITY_SCOPE_GLOBAL:
                callback.awaitInterfaceTethered();
                break;
            case CONNECTIVITY_SCOPE_LOCAL:
                callback.awaitInterfaceLocalOnly();
                break;
            default:
                fail("Unexpected connectivity type requested: " + connectivityType);
        }

        return callback;
    }

    private MyTetheringEventCallback enableEthernetTethering(String iface, Network expectedUpstream)
            throws Exception {
        return enableEthernetTethering(iface,
                new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setShouldShowEntitlementUi(false).build(), expectedUpstream);
    }

    private int getMTU(TestNetworkInterface iface) throws SocketException {
        NetworkInterface nif = NetworkInterface.getByName(iface.getInterfaceName());
        assertNotNull("Can't get NetworkInterface object for " + iface.getInterfaceName(), nif);
        return nif.getMTU();
    }

    private TapPacketReader makePacketReader(final TestNetworkInterface iface) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        return makePacketReader(fd, getMTU(iface));
    }

    private TapPacketReader makePacketReader(FileDescriptor fd, int mtu) {
        final TapPacketReader reader = new TapPacketReader(mHandler, fd, mtu);
        mHandler.post(() -> reader.start());
        HandlerUtils.waitForIdle(mHandler, TIMEOUT_MS);
        return reader;
    }

    private void checkVirtualEthernet(TestNetworkInterface iface, int mtu) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        mDownstreamReader = makePacketReader(fd, mtu);
        mTetheringEventCallback = enableEthernetTethering(iface.getInterfaceName(),
                null /* any upstream */);
        checkTetheredClientCallbacks(mDownstreamReader);
    }

    private void checkTetheredClientCallbacks(TapPacketReader packetReader) throws Exception {
        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        TetheringTester tester = new TetheringTester(packetReader);
        DhcpResults dhcpResults = tester.runDhcp(clientMacAddr);

        final Collection<TetheredClient> clients = mTetheringEventCallback.awaitClientConnected();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.iterator().next();

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(TETHERING_ETHERNET, client.getTetheringType());

        // Check the hostname.
        assertEquals(1, client.getAddresses().size());
        TetheredClient.AddressInfo info = client.getAddresses().get(0);
        assertEquals(TetheringTester.DHCP_HOSTNAME, info.getHostname());

        // Check the address is the one that was handed out in the DHCP ACK.
        assertLinkAddressMatches(dhcpResults.ipAddress, info.getAddress());

        // Check that the lifetime is correct +/- 10s.
        final long now = SystemClock.elapsedRealtime();
        final long actualLeaseDuration = (info.getAddress().getExpirationTime() - now) / 1000;
        final String msg = String.format("IP address should have lifetime of %d, got %d",
                dhcpResults.leaseDuration, actualLeaseDuration);
        assertTrue(msg, Math.abs(dhcpResults.leaseDuration - actualLeaseDuration) < 10);
    }

    private static final class TetheredInterfaceRequester implements TetheredInterfaceCallback {
        private final Handler mHandler;
        private final EthernetManager mEm;

        private TetheredInterfaceRequest mRequest;
        private final CompletableFuture<String> mFuture = new CompletableFuture<>();

        TetheredInterfaceRequester(Handler handler, EthernetManager em) {
            mHandler = handler;
            mEm = em;
        }

        @Override
        public void onAvailable(String iface) {
            Log.d(TAG, "Ethernet interface available: " + iface);
            mFuture.complete(iface);
        }

        @Override
        public void onUnavailable() {
            mFuture.completeExceptionally(new IllegalStateException("onUnavailable received"));
        }

        public CompletableFuture<String> requestInterface() {
            assertNull("BUG: more than one tethered interface request", mRequest);
            Log.d(TAG, "Requesting tethered interface");
            mRequest = mEm.requestTetheredInterface(mHandler::post, this);
            return mFuture;
        }

        public String getInterface() throws Exception {
            return requestInterface().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public void release() {
            if (mRequest != null) {
                mFuture.obtrudeException(new IllegalStateException("Request already released"));
                mRequest.release();
                mRequest = null;
            }
        }
    }

    public void assertLinkAddressMatches(LinkAddress l1, LinkAddress l2) {
        // Check all fields except the deprecation and expiry times.
        String msg = String.format("LinkAddresses do not match. expected: %s actual: %s", l1, l2);
        assertTrue(msg, l1.isSameAddressAs(l2));
        assertEquals("LinkAddress flags do not match", l1.getFlags(), l2.getFlags());
        assertEquals("LinkAddress scope does not match", l1.getScope(), l2.getScope());
    }

    private TetheringRequest requestWithStaticIpv4(String local, String client) {
        LinkAddress localAddr = local == null ? null : new LinkAddress(local);
        LinkAddress clientAddr = client == null ? null : new LinkAddress(client);
        return new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setShouldShowEntitlementUi(false).build();
    }

    private void assertInvalidStaticIpv4Request(String iface, String local, String client)
            throws Exception {
        try {
            enableEthernetTethering(iface, requestWithStaticIpv4(local, client),
                    null /* any upstream */);
            fail("Unexpectedly accepted invalid IPv4 configuration: " + local + ", " + client);
        } catch (IllegalArgumentException | NullPointerException expected) { }
    }

    private void assertInterfaceHasIpAddress(String iface, String expected) throws Exception {
        LinkAddress expectedAddr = new LinkAddress(expected);
        NetworkInterface nif = NetworkInterface.getByName(iface);
        for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
            final LinkAddress addr = new LinkAddress(ia.getAddress(), ia.getNetworkPrefixLength());
            if (expectedAddr.equals(addr)) {
                return;
            }
        }
        fail("Expected " + iface + " to have IP address " + expected + ", found "
                + nif.getInterfaceAddresses());
    }

    private TestNetworkInterface createTestInterface() throws Exception {
        TestNetworkManager tnm = mContext.getSystemService(TestNetworkManager.class);
        TestNetworkInterface iface = tnm.createTapInterface();
        Log.d(TAG, "Created test interface " + iface.getInterfaceName());
        return iface;
    }

    private void maybeDeleteTestInterface() throws Exception {
        if (mDownstreamIface != null) {
            mDownstreamIface.getFileDescriptor().close();
            Log.d(TAG, "Deleted test interface " + mDownstreamIface.getInterfaceName());
            mDownstreamIface = null;
        }
    }

    private TestNetworkTracker createTestUpstream(final List<LinkAddress> addresses,
            final List<InetAddress> dnses) throws Exception {
        mTm.setPreferTestNetworks(true);

        final LinkProperties lp = new LinkProperties();
        lp.setLinkAddresses(addresses);
        lp.setDnsServers(dnses);
        lp.setNat64Prefix(TEST_NAT64PREFIX);

        return initTestNetwork(mContext, lp, TIMEOUT_MS);
    }

    @Test
    public void testIcmpv6Echo() throws Exception {
        runPing6Test(initTetheringTester(toList(TEST_IP4_ADDR, TEST_IP6_ADDR),
                toList(TEST_IP4_DNS, TEST_IP6_DNS)));
    }

    private void runPing6Test(TetheringTester tester) throws Exception {
        TetheredDevice tethered = tester.createTetheredDevice(MacAddress.fromString("1:2:3:4:5:6"),
                true /* hasIpv6 */);
        Inet6Address remoteIp6Addr = (Inet6Address) parseNumericAddress("2400:222:222::222");
        ByteBuffer request = Ipv6Utils.buildEchoRequestPacket(tethered.macAddr,
                tethered.routerMacAddr, tethered.ipv6Addr, remoteIp6Addr);
        tester.verifyUpload(request, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));

            return isExpectedIcmpv6Packet(p, false /* hasEth */, ICMPV6_ECHO_REQUEST_TYPE);
        });

        ByteBuffer reply = Ipv6Utils.buildEchoReplyPacket(remoteIp6Addr, tethered.ipv6Addr);
        tester.verifyDownload(reply, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));

            return isExpectedIcmpv6Packet(p, true /* hasEth */, ICMPV6_ECHO_REPLY_TYPE);
        });
    }

    // Test network topology:
    //
    //         public network (rawip)                 private network
    //                   |                 UE                |
    // +------------+    V    +------------+------------+    V    +------------+
    // |   Sever    +---------+  Upstream  | Downstream +---------+   Client   |
    // +------------+         +------------+------------+         +------------+
    // remote ip              public ip                           private ip
    // 8.8.8.8:443            <Upstream ip>:9876                  <TetheredDevice ip>:9876
    //
    private static final Inet4Address REMOTE_IP4_ADDR =
            (Inet4Address) parseNumericAddress("8.8.8.8");
    // Used by public port and private port. Assume port 9876 has not been used yet before the
    // testing that public port and private port are the same in the testing. Note that NAT port
    // forwarding could be different between private port and public port.
    // TODO: move to the start of test class.
    private static final short LOCAL_PORT = 9876;
    private static final short REMOTE_PORT = 433;
    private static final byte TYPE_OF_SERVICE = 0;
    private static final short ID = 27149;
    private static final short FLAGS_AND_FRAGMENT_OFFSET = (short) 0x4000; // flags=DF, offset=0
    private static final byte TIME_TO_LIVE = (byte) 0x40;
    private static final ByteBuffer PAYLOAD =
            ByteBuffer.wrap(new byte[] { (byte) 0x12, (byte) 0x34 });
    private static final ByteBuffer PAYLOAD2 =
            ByteBuffer.wrap(new byte[] { (byte) 0x56, (byte) 0x78 });
    private static final ByteBuffer PAYLOAD3 =
            ByteBuffer.wrap(new byte[] { (byte) 0x9a, (byte) 0xbc });

    @NonNull
    private ByteBuffer buildUdpPacket(
            @Nullable final MacAddress srcMac, @Nullable final MacAddress dstMac,
            @NonNull final InetAddress srcIp, @NonNull final InetAddress dstIp,
            short srcPort, short dstPort, @Nullable final ByteBuffer payload)
            throws Exception {
        int ipProto;
        short ethType;
        if (srcIp instanceof Inet4Address && dstIp instanceof Inet4Address) {
            ipProto = IPPROTO_IP;
            ethType = (short) ETHER_TYPE_IPV4;
        } else if (srcIp instanceof Inet6Address && dstIp instanceof Inet6Address) {
            ipProto = IPPROTO_IPV6;
            ethType = (short) ETHER_TYPE_IPV6;
        } else {
            fail("Unsupported conditions: srcIp " + srcIp + ", dstIp " + dstIp);
            // Make compiler happy to the uninitialized ipProto and ethType.
            return null;  // unreachable, the annotation @NonNull of function return value is true.
        }

        final boolean hasEther = (srcMac != null && dstMac != null);
        final int payloadLen = (payload == null) ? 0 : payload.limit();
        final ByteBuffer buffer = PacketBuilder.allocate(hasEther, ipProto, IPPROTO_UDP,
                payloadLen);
        final PacketBuilder packetBuilder = new PacketBuilder(buffer);

        // [1] Ethernet header
        if (hasEther) packetBuilder.writeL2Header(srcMac, dstMac, (short) ETHER_TYPE_IPV4);

        // [2] IP header
        if (ipProto == IPPROTO_IP) {
            packetBuilder.writeIpv4Header(TYPE_OF_SERVICE, ID, FLAGS_AND_FRAGMENT_OFFSET,
                    TIME_TO_LIVE, (byte) IPPROTO_UDP, (Inet4Address) srcIp, (Inet4Address) dstIp);
        } else {
            packetBuilder.writeIpv6Header(VERSION_TRAFFICCLASS_FLOWLABEL, (byte) IPPROTO_UDP,
                    HOP_LIMIT, (Inet6Address) srcIp, (Inet6Address) dstIp);
        }

        // [3] UDP header
        packetBuilder.writeUdpHeader(srcPort, dstPort);

        // [4] Payload
        if (payload != null) {
            buffer.put(payload);
            // in case data might be reused by caller, restore the position and
            // limit of bytebuffer.
            payload.clear();
        }

        return packetBuilder.finalizePacket();
    }

    @NonNull
    private ByteBuffer buildUdpPacket(@NonNull final InetAddress srcIp,
            @NonNull final InetAddress dstIp, short srcPort, short dstPort,
            @Nullable final ByteBuffer payload) throws Exception {
        return buildUdpPacket(null /* srcMac */, null /* dstMac */, srcIp, dstIp, srcPort,
                dstPort, payload);
    }

    // TODO: remove ipv4 verification (is4To6 = false) once upstream connected notification race is
    // fixed. See #runUdp4Test.
    //
    // This function sends a probe packet to downstream interface and exam the result from upstream
    // interface to make sure ipv4 tethering is ready. Return the entire packet which received from
    // upstream interface.
    @NonNull
    private byte[] probeV4TetheringConnectivity(TetheringTester tester, TetheredDevice tethered,
            boolean is4To6) throws Exception {
        final ByteBuffer probePacket = buildUdpPacket(tethered.macAddr,
                tethered.routerMacAddr, tethered.ipv4Addr /* srcIp */,
                REMOTE_IP4_ADDR /* dstIp */, LOCAL_PORT /* srcPort */, REMOTE_PORT /* dstPort */,
                TEST_REACHABILITY_PAYLOAD);

        // Send a UDP packet from client and check the packet can be found on upstream interface.
        for (int i = 0; i < TETHER_REACHABILITY_ATTEMPTS; i++) {
            byte[] expectedPacket = tester.testUpload(probePacket, p -> {
                Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
                // If is4To6 is true, the ipv4 probe packet would be translated to ipv6 by Clat and
                // would see this translated ipv6 packet in upstream interface.
                return isExpectedUdpPacket(p, false /* hasEther */, !is4To6 /* isIpv4 */,
                        TEST_REACHABILITY_PAYLOAD);
            });
            if (expectedPacket != null) return expectedPacket;
        }

        fail("Can't verify " + (is4To6 ? "ipv4 to ipv6" : "ipv4") + " tethering connectivity after "
                + TETHER_REACHABILITY_ATTEMPTS + " attempts");
        return null;
    }

    private void runUdp4Test(TetheringTester tester, boolean usingBpf) throws Exception {
        final TetheredDevice tethered = tester.createTetheredDevice(MacAddress.fromString(
                "1:2:3:4:5:6"), false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // Because async upstream connected notification can't guarantee the tethering routing is
        // ready to use. Need to test tethering connectivity before testing.
        // For short term plan, consider using IPv6 RA to get MAC address because the prefix comes
        // from upstream. That can guarantee that the routing is ready. Long term plan is that
        // refactors upstream connected notification from async to sync.
        probeV4TetheringConnectivity(tester, tethered, false /* is4To6 */);

        // Send a UDP packet in original direction.
        final ByteBuffer originalPacket = buildUdpPacket(tethered.macAddr,
                tethered.routerMacAddr, tethered.ipv4Addr /* srcIp */,
                REMOTE_IP4_ADDR /* dstIp */, LOCAL_PORT /* srcPort */, REMOTE_PORT /* dstPort */,
                PAYLOAD /* payload */);
        tester.verifyUpload(originalPacket, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
            return isExpectedUdpPacket(p, false /* hasEther */, true /* isIpv4 */, PAYLOAD);
        });

        // Send a UDP packet in reply direction.
        final Inet4Address publicIp4Addr = (Inet4Address) TEST_IP4_ADDR.getAddress();
        final ByteBuffer replyPacket = buildUdpPacket(REMOTE_IP4_ADDR /* srcIp */,
                publicIp4Addr /* dstIp */, REMOTE_PORT /* srcPort */, LOCAL_PORT /* dstPort */,
                PAYLOAD2 /* payload */);
        tester.verifyDownload(replyPacket, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));
            return isExpectedUdpPacket(p, true /* hasEther */, true /* isIpv4 */, PAYLOAD2);
        });

        if (usingBpf) {
            // Send second UDP packet in original direction.
            // The BPF coordinator only offloads the ASSURED conntrack entry. The "request + reply"
            // packets can make status IPS_SEEN_REPLY to be set. Need one more packet to make
            // conntrack status IPS_ASSURED_BIT to be set. Note the third packet needs to delay
            // 2 seconds because kernel monitors a UDP connection which still alive after 2 seconds
            // and apply ASSURED flag.
            // See kernel upstream commit b7b1d02fc43925a4d569ec221715db2dfa1ce4f5 and
            // nf_conntrack_udp_packet in net/netfilter/nf_conntrack_proto_udp.c
            Thread.sleep(UDP_STREAM_TS_MS);
            final ByteBuffer originalPacket2 = buildUdpPacket(tethered.macAddr,
                    tethered.routerMacAddr, tethered.ipv4Addr /* srcIp */,
                    REMOTE_IP4_ADDR /* dstIp */, LOCAL_PORT /* srcPort */,
                    REMOTE_PORT /* dstPort */, PAYLOAD3 /* payload */);
            tester.verifyUpload(originalPacket2, p -> {
                Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
                return isExpectedUdpPacket(p, false /* hasEther */, true /* isIpv4 */, PAYLOAD3);
            });

            // [1] Verify IPv4 upstream rule map.
            final HashMap<Tether4Key, Tether4Value> upstreamMap = pollRawMapFromDump(
                    Tether4Key.class, Tether4Value.class, DUMPSYS_RAWMAP_ARG_UPSTREAM4);
            assertNotNull(upstreamMap);
            assertEquals(1, upstreamMap.size());

            final Map.Entry<Tether4Key, Tether4Value> rule =
                    upstreamMap.entrySet().iterator().next();

            final Tether4Key upstream4Key = rule.getKey();
            assertEquals(IPPROTO_UDP, upstream4Key.l4proto);
            assertTrue(Arrays.equals(tethered.ipv4Addr.getAddress(), upstream4Key.src4));
            assertEquals(LOCAL_PORT, upstream4Key.srcPort);
            assertTrue(Arrays.equals(REMOTE_IP4_ADDR.getAddress(), upstream4Key.dst4));
            assertEquals(REMOTE_PORT, upstream4Key.dstPort);

            final Tether4Value upstream4Value = rule.getValue();
            assertTrue(Arrays.equals(publicIp4Addr.getAddress(),
                    InetAddress.getByAddress(upstream4Value.src46).getAddress()));
            assertEquals(LOCAL_PORT, upstream4Value.srcPort);
            assertTrue(Arrays.equals(REMOTE_IP4_ADDR.getAddress(),
                    InetAddress.getByAddress(upstream4Value.dst46).getAddress()));
            assertEquals(REMOTE_PORT, upstream4Value.dstPort);

            // [2] Verify stats map.
            // Transmit packets on both direction for verifying stats. Because we only care the
            // packet count in stats test, we just reuse the existing packets to increaes
            // the packet count on both direction.

            // Send packets on original direction.
            for (int i = 0; i < TX_UDP_PACKET_COUNT; i++) {
                tester.verifyUpload(originalPacket, p -> {
                    Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
                    return isExpectedUdpPacket(p, false /* hasEther */, true /* isIpv4 */, PAYLOAD);
                });
            }

            // Send packets on reply direction.
            for (int i = 0; i < RX_UDP_PACKET_COUNT; i++) {
                tester.verifyDownload(replyPacket, p -> {
                    Log.d(TAG, "Packet in downstream: " + dumpHexString(p));
                    return isExpectedUdpPacket(p, true /* hasEther */, true /* isIpv4 */, PAYLOAD2);
                });
            }

            // Dump stats map to verify.
            final HashMap<TetherStatsKey, TetherStatsValue> statsMap = pollRawMapFromDump(
                    TetherStatsKey.class, TetherStatsValue.class, DUMPSYS_RAWMAP_ARG_STATS);
            assertNotNull(statsMap);
            assertEquals(1, statsMap.size());

            final Map.Entry<TetherStatsKey, TetherStatsValue> stats =
                    statsMap.entrySet().iterator().next();

            // TODO: verify the upstream index in TetherStatsKey.

            final TetherStatsValue statsValue = stats.getValue();
            assertEquals(RX_UDP_PACKET_COUNT, statsValue.rxPackets);
            assertEquals(RX_UDP_PACKET_COUNT * RX_UDP_PACKET_SIZE, statsValue.rxBytes);
            assertEquals(0, statsValue.rxErrors);
            assertEquals(TX_UDP_PACKET_COUNT, statsValue.txPackets);
            assertEquals(TX_UDP_PACKET_COUNT * TX_UDP_PACKET_SIZE, statsValue.txBytes);
            assertEquals(0, statsValue.txErrors);
        }
    }

    private TetheringTester initTetheringTester(List<LinkAddress> upstreamAddresses,
            List<InetAddress> upstreamDnses) throws Exception {
        assumeFalse(mEm.isAvailable());

        // MyTetheringEventCallback currently only support await first available upstream. Tethering
        // may select internet network as upstream if test network is not available and not be
        // preferred yet. Create test upstream network before enable tethering.
        mUpstreamTracker = createTestUpstream(upstreamAddresses, upstreamDnses);

        mDownstreamIface = createTestInterface();
        mEm.setIncludeTestInterfaces(true);

        // Make sure EtherentTracker use "mDownstreamIface" as server mode interface.
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), mTetheredInterfaceRequester.getInterface());

        mTetheringEventCallback = enableEthernetTethering(mDownstreamIface.getInterfaceName(),
                mUpstreamTracker.getNetwork());
        assertEquals("onUpstreamChanged for unexpected network", mUpstreamTracker.getNetwork(),
                mTetheringEventCallback.awaitUpstreamChanged());

        mDownstreamReader = makePacketReader(mDownstreamIface);
        mUpstreamReader = makePacketReader(mUpstreamTracker.getTestIface());

        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        // Currently tethering don't have API to tell when ipv6 tethering is available. Thus, make
        // sure tethering already have ipv6 connectivity before testing.
        if (cm.getLinkProperties(mUpstreamTracker.getNetwork()).hasGlobalIpv6Address()) {
            waitForRouterAdvertisement(mDownstreamReader, mDownstreamIface.getInterfaceName(),
                    WAIT_RA_TIMEOUT_MS);
        }

        return new TetheringTester(mDownstreamReader, mUpstreamReader);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.R)
    public void testTetherUdpV4UpToR() throws Exception {
        runUdp4Test(initTetheringTester(toList(TEST_IP4_ADDR), toList(TEST_IP4_DNS)),
                false /* usingBpf */);
    }

    private static boolean isUdpOffloadSupportedByKernel(final String kernelVersion) {
        final KVersion current = DeviceInfoUtils.getMajorMinorSubminorVersion(kernelVersion);
        return current.isInRange(new KVersion(4, 14, 222), new KVersion(4, 19, 0))
                || current.isInRange(new KVersion(4, 19, 176), new KVersion(5, 4, 0))
                || current.isAtLeast(new KVersion(5, 4, 98));
    }

    @Test
    public void testIsUdpOffloadSupportedByKernel() throws Exception {
        assertFalse(isUdpOffloadSupportedByKernel("4.14.221"));
        assertTrue(isUdpOffloadSupportedByKernel("4.14.222"));
        assertTrue(isUdpOffloadSupportedByKernel("4.16.0"));
        assertTrue(isUdpOffloadSupportedByKernel("4.18.0"));
        assertFalse(isUdpOffloadSupportedByKernel("4.19.0"));

        assertFalse(isUdpOffloadSupportedByKernel("4.19.175"));
        assertTrue(isUdpOffloadSupportedByKernel("4.19.176"));
        assertTrue(isUdpOffloadSupportedByKernel("5.2.0"));
        assertTrue(isUdpOffloadSupportedByKernel("5.3.0"));
        assertFalse(isUdpOffloadSupportedByKernel("5.4.0"));

        assertFalse(isUdpOffloadSupportedByKernel("5.4.97"));
        assertTrue(isUdpOffloadSupportedByKernel("5.4.98"));
        assertTrue(isUdpOffloadSupportedByKernel("5.10.0"));
    }

    // TODO: refactor test testTetherUdpV4* into IPv4 UDP non-offload and offload tests.
    // That can be easier to know which feature is verified from test results.
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherUdpV4AfterR() throws Exception {
        final String kernelVersion = VintfRuntimeInfo.getKernelVersion();
        final boolean isUdpOffloadSupported = isUdpOffloadSupportedByKernel(kernelVersion);
        if (!isUdpOffloadSupported) {
            Log.i(TAG, "testTetherUdpV4AfterR will skip BPF offload test for kernel "
                    + kernelVersion);
        }
        final boolean isTetherConfigBpfOffloadEnabled = isTetherConfigBpfOffloadEnabled();
        if (!isTetherConfigBpfOffloadEnabled) {
            Log.i(TAG, "testTetherUdpV4AfterR will skip BPF offload test "
                    + "because tethering config doesn't enable BPF offload.");
        }
        runUdp4Test(initTetheringTester(toList(TEST_IP4_ADDR), toList(TEST_IP4_DNS)),
                isUdpOffloadSupported && isTetherConfigBpfOffloadEnabled);
    }

    @Nullable
    private <K extends Struct, V extends Struct> Pair<K, V> parseMapKeyValue(
            Class<K> keyClass, Class<V> valueClass, @NonNull String dumpStr) {
        Log.w(TAG, "Parsing string: " + dumpStr);

        String[] keyValueStrs = dumpStr.split(BASE64_DELIMITER);
        if (keyValueStrs.length != 2 /* key + value */) {
            fail("The length is " + keyValueStrs.length + " but expect 2. "
                    + "Split string(s): " + TextUtils.join(",", keyValueStrs));
        }

        final byte[] keyBytes = Base64.decode(keyValueStrs[0], Base64.DEFAULT);
        Log.d(TAG, "keyBytes: " + dumpHexString(keyBytes));
        final ByteBuffer keyByteBuffer = ByteBuffer.wrap(keyBytes);
        keyByteBuffer.order(ByteOrder.nativeOrder());
        final K k = Struct.parse(keyClass, keyByteBuffer);

        final byte[] valueBytes = Base64.decode(keyValueStrs[1], Base64.DEFAULT);
        Log.d(TAG, "valueBytes: " + dumpHexString(valueBytes));
        final ByteBuffer valueByteBuffer = ByteBuffer.wrap(valueBytes);
        valueByteBuffer.order(ByteOrder.nativeOrder());
        final V v = Struct.parse(valueClass, valueByteBuffer);

        return new Pair<>(k, v);
    }

    @NonNull
    private <K extends Struct, V extends Struct> HashMap<K, V> dumpAndParseRawMap(
            Class<K> keyClass, Class<V> valueClass, @NonNull String mapArg)
            throws Exception {
        final String[] args = new String[] {DUMPSYS_TETHERING_RAWMAP_ARG, mapArg};
        final String rawMapStr = DumpTestUtils.dumpService(Context.TETHERING_SERVICE, args);
        final HashMap<K, V> map = new HashMap<>();

        for (final String line : rawMapStr.split(LINE_DELIMITER)) {
            final Pair<K, V> rule = parseMapKeyValue(keyClass, valueClass, line.trim());
            map.put(rule.first, rule.second);
        }
        return map;
    }

    @Nullable
    private <K extends Struct, V extends Struct> HashMap<K, V> pollRawMapFromDump(
            Class<K> keyClass, Class<V> valueClass, @NonNull String mapArg)
            throws Exception {
        for (int retryCount = 0; retryCount < DUMP_POLLING_MAX_RETRY; retryCount++) {
            final HashMap<K, V> map = dumpAndParseRawMap(keyClass, valueClass, mapArg);
            if (!map.isEmpty()) return map;

            Thread.sleep(DUMP_POLLING_INTERVAL_MS);
        }

        fail("Cannot get rules after " + DUMP_POLLING_MAX_RETRY * DUMP_POLLING_INTERVAL_MS + "ms");
        return null;
    }

    private boolean isTetherConfigBpfOffloadEnabled() throws Exception {
        final String dumpStr = DumpTestUtils.dumpService(Context.TETHERING_SERVICE, "--short");

        // BPF offload tether config can be overridden by "config_tether_enable_bpf_offload" in
        // packages/modules/Connectivity/Tethering/res/values/config.xml. OEM may disable config by
        // RRO to override the enabled default value. Get the tethering config via dumpsys.
        // $ dumpsys tethering
        //   mIsBpfEnabled: true
        boolean enabled = dumpStr.contains("mIsBpfEnabled: true");
        if (!enabled) {
            Log.d(TAG, "BPF offload tether config not enabled: " + dumpStr);
        }
        return enabled;
    }

    @NonNull
    private Inet6Address getClatIpv6Address(TetheringTester tester, TetheredDevice tethered)
            throws Exception {
        // Send an IPv4 UDP packet from client and check that a CLAT translated IPv6 UDP packet can
        // be found on upstream interface. Get CLAT IPv6 address from the CLAT translated IPv6 UDP
        // packet.
        byte[] expectedPacket = probeV4TetheringConnectivity(tester, tethered, true /* is4To6 */);

        // Above has guaranteed that the found packet is an IPv6 packet without ether header.
        return Struct.parse(Ipv6Header.class, ByteBuffer.wrap(expectedPacket)).srcIp;
    }

    // Test network topology:
    //
    //            public network (rawip)                 private network
    //                      |         UE (CLAT support)         |
    // +---------------+    V    +------------+------------+    V    +------------+
    // | NAT64 Gateway +---------+  Upstream  | Downstream +---------+   Client   |
    // +---------------+         +------------+------------+         +------------+
    // remote ip                 public ip                           private ip
    // [64:ff9b::808:808]:443    [clat ipv6]:9876                    [TetheredDevice ipv4]:9876
    //
    // Note that CLAT IPv6 address is generated by ClatCoordinator. Get the CLAT IPv6 address by
    // sending out an IPv4 packet and extracting the source address from CLAT translated IPv6
    // packet.
    //
    private void runClatUdpTest(TetheringTester tester) throws Exception {
        final TetheredDevice tethered = tester.createTetheredDevice(MacAddress.fromString(
                "1:2:3:4:5:6"), true /* hasIpv6 */);

        // Get CLAT IPv6 address.
        final Inet6Address clatAddr6 = getClatIpv6Address(tester, tethered);

        // Send an IPv4 UDP packet in original direction.
        // IPv4 packet -- CLAT translation --> IPv6 packet
        final ByteBuffer originalPacket = buildUdpPacket(tethered.macAddr,
                tethered.routerMacAddr, tethered.ipv4Addr /* srcIp */,
                REMOTE_IP4_ADDR /* dstIp */, LOCAL_PORT /* srcPort */, REMOTE_PORT /* dstPort */,
                PAYLOAD /* payload */);
        tester.verifyUpload(originalPacket, p -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));
            return isExpectedUdpPacket(p, false /* hasEther */, false /* isIpv4 */, PAYLOAD);
        });

        // Send an IPv6 UDP packet in reply direction.
        // IPv6 packet -- CLAT translation --> IPv4 packet
        final ByteBuffer replyPacket = buildUdpPacket(REMOTE_NAT64_ADDR /* srcIp */,
                clatAddr6 /* dstIp */, REMOTE_PORT /* srcPort */, LOCAL_PORT /* dstPort */,
                PAYLOAD2 /* payload */);
        tester.verifyDownload(replyPacket, p -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));
            return isExpectedUdpPacket(p, true /* hasEther */, true /* isIpv4 */, PAYLOAD2);
        });

        // TODO: test CLAT bpf maps.
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherClatUdp() throws Exception {
        // CLAT only starts on IPv6 only network.
        runClatUdpTest(initTetheringTester(toList(TEST_IP6_ADDR), toList(TEST_IP6_DNS)));
    }

    private <T> List<T> toList(T... array) {
        return Arrays.asList(array);
    }
}
