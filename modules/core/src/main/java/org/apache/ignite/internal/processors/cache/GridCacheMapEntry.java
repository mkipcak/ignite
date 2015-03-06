/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.eviction.*;
import org.apache.ignite.internal.managers.deployment.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.extras.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.processors.dr.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.offheap.unsafe.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.expiry.*;
import javax.cache.processor.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.events.EventType.*;
import static org.apache.ignite.internal.processors.cache.CacheFlag.*;
import static org.apache.ignite.internal.processors.dr.GridDrType.*;
import static org.apache.ignite.transactions.TransactionState.*;

/**
 * Adapter for cache entry.
 */
@SuppressWarnings({
    "NonPrivateFieldAccessedInSynchronizedContext", "TooBroadScope", "FieldAccessedSynchronizedAndUnsynchronized"})
public abstract class GridCacheMapEntry implements GridCacheEntryEx {
    /** */
    private static final byte IS_DELETED_MASK = 0x01;

    /** */
    private static final byte IS_UNSWAPPED_MASK = 0x02;

    /** */
    public static final Comparator<GridCacheVersion> ATOMIC_VER_COMPARATOR = new GridCacheAtomicVersionComparator();

    /**
     * NOTE
     * ====
     * Make sure to recalculate this value any time when adding or removing fields from entry.
     * The size should be count as follows:
     * <ul>
     * <li>Primitives: byte/boolean = 1, short = 2, int/float = 4, long/double = 8</li>
     * <li>References: 8 each</li>
     * <li>Each nested object should be analyzed in the same way as above.</li>
     * </ul>
     */
    // 7 * 8 /*references*/  + 2 * 8 /*long*/  + 1 * 4 /*int*/ + 1 * 1 /*byte*/ = 77
    private static final int SIZE_OVERHEAD = 77 /*entry*/ + 32 /* version */ + 4 * 7 /* key + val */;

    /** Static logger to avoid re-creation. Made static for test purpose. */
    protected static final AtomicReference<IgniteLogger> logRef = new AtomicReference<>();

    /** Logger. */
    protected static volatile IgniteLogger log;

    /** Cache registry. */
    @GridToStringExclude
    protected final GridCacheContext<?, ?> cctx;

    /** Key. */
    @GridToStringInclude
    protected final KeyCacheObject key;

    /** Value. */
    @GridToStringInclude
    protected CacheObject val;

    /** Start version. */
    @GridToStringInclude
    protected final long startVer;

    /** Version. */
    @GridToStringInclude
    protected GridCacheVersion ver;

    /** Next entry in the linked list. */
    @GridToStringExclude
    private volatile GridCacheMapEntry next0;

    /** Next entry in the linked list. */
    @GridToStringExclude
    private volatile GridCacheMapEntry next1;

    /** Key hash code. */
    @GridToStringInclude
    private final int hash;

    /** Off-heap value pointer. */
    protected long valPtr;

    /** Extras */
    @GridToStringInclude
    private GridCacheEntryExtras extras;

    /**
     * Flags:
     * <ul>
     *     <li>Deleted flag - mask {@link #IS_DELETED_MASK}</li>
     *     <li>Unswapped flag - mask {@link #IS_UNSWAPPED_MASK}</li>
     * </ul>
     */
    @GridToStringInclude
    protected byte flags;

    /**
     * @param cctx Cache context.
     * @param key Cache key.
     * @param hash Key hash value.
     * @param val Entry value.
     * @param next Next entry in the linked list.
     * @param ttl Time to live.
     * @param hdrId Header id.
     */
    protected GridCacheMapEntry(GridCacheContext<?, ?> cctx,
        KeyCacheObject key,
        int hash,
        CacheObject val,
        GridCacheMapEntry next,
        long ttl,
        int hdrId)
    {
        log = U.logger(cctx.kernalContext(), logRef, GridCacheMapEntry.class);

        key = (KeyCacheObject)cctx.kernalContext().portable().prepareForCache(key, cctx);

        assert key != null;

        this.key = key;
        this.hash = hash;
        this.cctx = cctx;

        ttlAndExpireTimeExtras(ttl, CU.toExpireTime(ttl));

        val = cctx.kernalContext().portable().prepareForCache(val, cctx);

        synchronized (this) {
            value(val);
        }

        next(hdrId, next);

        ver = cctx.versions().next();

        startVer = ver.order();
    }

    /** {@inheritDoc} */
    @Override public long startVersion() {
        return startVer;
    }

    /**
     * Sets entry value. If off-heap value storage is enabled, will serialize value to off-heap.
     *
     * @param val Value to store.
     */
    protected void value(@Nullable CacheObject val) {
        assert Thread.holdsLock(this);

        // In case we deal with IGFS cache, count updated data
        if (cctx.cache().isIgfsDataCache() &&
            cctx.kernalContext().igfsHelper().isIgfsBlockKey(key.value(cctx.cacheObjectContext(), false))) {
            int newSize = valueLength0(val, null);
            int oldSize = valueLength0(this.val, (this.val == null && valPtr != 0) ? valueBytes0() : null);

            int delta = newSize - oldSize;

            if (delta != 0 && !cctx.isNear())
                cctx.cache().onIgfsDataSizeChanged(delta);
        }

        if (!isOffHeapValuesOnly()) {
            this.val = val;

            valPtr = 0;
        }
        else {
            try {
                if (cctx.kernalContext().config().isPeerClassLoadingEnabled()) {
                    Object val0 = null;

                    if (val != null && val.type() != CacheObjectAdapter.TYPE_BYTE_ARR) {
                        val0 = cctx.portable().unmarshal(cctx.cacheObjectContext(),
                            val.valueBytes(cctx.cacheObjectContext()), cctx.deploy().globalLoader());

                        if (val0 != null)
                            cctx.gridDeploy().deploy(val0.getClass(), val0.getClass().getClassLoader());
                    }

                    if (U.p2pLoader(val0)) {
                        cctx.deploy().addDeploymentContext(
                            new GridDeploymentInfoBean((GridDeploymentInfo)val0.getClass().getClassLoader()));
                    }
                }

                GridUnsafeMemory mem = cctx.unsafeMemory();

                assert mem != null;

                if (val != null) {
                    byte type = val.type();

                    valPtr = mem.putOffHeap(valPtr, val.valueBytes(cctx.cacheObjectContext()), type);
                }
                else {
                    mem.removeOffHeap(valPtr);

                    valPtr = 0;
                }
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to deserialize value [entry=" + this + ", val=" + val + ']');

                throw new IgniteException(e);
            }
        }
    }

    /**
     * Isolated method to get length of IGFS block.
     *
     * @param val Value.
     * @param valBytes Value bytes.
     * @return Length of value.
     */
    private int valueLength0(@Nullable CacheObject val, @Nullable IgniteBiTuple<byte[], Byte> valBytes) {
        byte[] bytes = val != null ? (byte[])val.value(cctx.cacheObjectContext(), false) : null;

        if (bytes != null)
            return bytes.length;

        if (valBytes == null)
            return 0;

        return valBytes.get1().length - (((valBytes.get2() == CacheObjectAdapter.TYPE_BYTE_ARR) ? 0 : 6));
    }

    /**
     * @return Value bytes.
     */
    protected CacheObject valueBytesUnlocked() {
        assert Thread.holdsLock(this);

        CacheObject val0 = val;

        if (val0 == null && valPtr != 0) {
            IgniteBiTuple<byte[], Byte> t = valueBytes0();

            return cctx.portable().toCacheObject(cctx.cacheObjectContext(), t.get2(), t.get1());
        }

        return val0;
    }

    /** {@inheritDoc} */
    @Override public int memorySize() throws IgniteCheckedException {
        byte[] kb;
        byte[] vb = null;

        int extrasSize;

        synchronized (this) {
            key.prepareMarshal(cctx.cacheObjectContext());

            kb = key.valueBytes(cctx.cacheObjectContext());

            if (val != null) {
                val.prepareMarshal(cctx.cacheObjectContext());

                vb = val.valueBytes(cctx.cacheObjectContext());
            }

           extrasSize = extrasSize();
        }

        return SIZE_OVERHEAD + extrasSize + kb.length + (vb == null ? 1 : vb.length);
    }

    /** {@inheritDoc} */
    @Override public boolean isInternal() {
        return key.internal();
    }

    /** {@inheritDoc} */
    @Override public boolean isDht() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isLocal() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isNear() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isReplicated() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean detached() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public <K, V> GridCacheContext<K, V> context() {
        return (GridCacheContext<K, V>)cctx;
    }

    /** {@inheritDoc} */
    @Override public boolean isNew() throws GridCacheEntryRemovedException {
        assert Thread.holdsLock(this);

        checkObsolete();

        return isStartVersion();
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean isNewLocked() throws GridCacheEntryRemovedException {
        checkObsolete();

        return isStartVersion();
    }

    /**
     * @return {@code True} if start version.
     */
    public boolean isStartVersion() {
        return ver.nodeOrder() == cctx.localNode().order() && ver.order() == startVer;
    }

    /** {@inheritDoc} */
    @Override public boolean valid(long topVer) {
        return true;
    }

    /** {@inheritDoc} */
    @Override public int partition() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override public boolean partitionValid() {
        return true;
    }

    /** {@inheritDoc} */
    @Nullable @Override public GridCacheEntryInfo info() {
        GridCacheEntryInfo info = null;

        long time = U.currentTimeMillis();

        synchronized (this) {
            if (!obsolete()) {
                info = new GridCacheEntryInfo();

                info.key(key);
                info.cacheId(cctx.cacheId());

                long expireTime = expireTimeExtras();

                boolean expired = expireTime != 0 && expireTime <= time;

                info.ttl(ttlExtras());
                info.expireTime(expireTime);
                info.version(ver);
                info.setNew(isStartVersion());
                info.setDeleted(deletedUnlocked());

                if (!expired)
                    info.value(valueBytesUnlocked());
            }
        }

        return info;
    }

    /** {@inheritDoc} */
    @Override public CacheObject unswap() throws IgniteCheckedException {
        return unswap(false, true);
    }

    /**
     * Unswaps an entry.
     *
     * @param ignoreFlags Whether to ignore swap flags.
     * @param needVal If {@code false} then do not to deserialize value during unswap.
     * @return Value.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable @Override public CacheObject unswap(boolean ignoreFlags, boolean needVal) throws IgniteCheckedException {
        boolean swapEnabled = cctx.swap().swapEnabled() && (ignoreFlags || !cctx.hasFlag(SKIP_SWAP));

        if (!swapEnabled && !cctx.isOffHeapEnabled())
            return null;

        synchronized (this) {
            if (isStartVersion() && ((flags & IS_UNSWAPPED_MASK) == 0)) {
                GridCacheSwapEntry e;

                if (cctx.offheapTiered()) {
                    e = cctx.swap().readOffheapPointer(this);

                    if (e != null) {
                        if (e.offheapPointer() > 0) {
                            valPtr = e.offheapPointer();

                            if (needVal) {
                                CacheObject val = cctx.fromOffheap(valPtr, false);

                                e.value(val);
                            }
                        }
                        else // Read from swap.
                            valPtr = 0;
                    }
                }
                else
                    e = detached() ? cctx.swap().read(this, true, true, true) : cctx.swap().readAndRemove(this);

                if (log.isDebugEnabled())
                    log.debug("Read swap entry [swapEntry=" + e + ", cacheEntry=" + this + ']');

                flags |= IS_UNSWAPPED_MASK;

                // If there is a value.
                if (e != null) {
                    long delta = e.expireTime() == 0 ? 0 : e.expireTime() - U.currentTimeMillis();

                    if (delta >= 0) {
                        CacheObject val = e.value();

                        val = cctx.kernalContext().portable().prepareForCache(val, cctx);

                        // Set unswapped value.
                        update(val, e.expireTime(), e.ttl(), e.version());

                        // Must update valPtr again since update() will reset it.
                        if (cctx.offheapTiered() && e.offheapPointer() > 0)
                            valPtr = e.offheapPointer();

                        return val;
                    }
                    else
                        clearIndex(e.value());
                }
            }
        }

        return null;
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void swap() throws IgniteCheckedException {
        if (cctx.isSwapOrOffheapEnabled() && !deletedUnlocked() && hasValueUnlocked() && !detached()) {
            assert Thread.holdsLock(this);

            long expireTime = expireTimeExtras();

            if (expireTime > 0 && U.currentTimeMillis() >= expireTime) { // Don't swap entry if it's expired.
                // Entry might have been updated.
                if (cctx.offheapTiered()) {
                    cctx.swap().removeOffheap(key);

                    valPtr = 0;
                }

                return;
            }

            if (val == null && cctx.offheapTiered() && valPtr != 0) {
                if (log.isDebugEnabled())
                    log.debug("Value did not change, skip write swap entry: " + this);

                if (cctx.swap().offheapEvictionEnabled())
                    cctx.swap().enableOffheapEviction(key());

                return;
            }

            IgniteUuid valClsLdrId = null;

            if (val != null) {
                valClsLdrId = cctx.deploy().getClassLoaderId(
                    val.value(cctx.cacheObjectContext(), false).getClass().getClassLoader());
            }

            IgniteBiTuple<byte[], Byte> valBytes = valueBytes0();

            cctx.swap().write(key(),
                ByteBuffer.wrap(valBytes.get1()),
                valBytes.get2(),
                ver,
                ttlExtras(),
                expireTime,
                cctx.deploy().getClassLoaderId(U.detectObjectClassLoader(key.value(cctx.cacheObjectContext(), false))),
                valClsLdrId);

            if (log.isDebugEnabled())
                log.debug("Wrote swap entry: " + this);
        }
    }

    /**
     * @return Value bytes and flag indicating whether value is byte array.
     */
    protected IgniteBiTuple<byte[], Byte> valueBytes0() {
        assert Thread.holdsLock(this);

        if (valPtr != 0) {
            assert isOffHeapValuesOnly() || cctx.offheapTiered();

            return cctx.unsafeMemory().get(valPtr);
        }
        else {
            assert val != null;

            try {
                byte[] bytes = val.valueBytes(cctx.cacheObjectContext());

                return new IgniteBiTuple<>(bytes, val.type());
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    protected final void releaseSwap() throws IgniteCheckedException {
        if (cctx.isSwapOrOffheapEnabled()) {
            synchronized (this){
                cctx.swap().remove(key());
            }

            if (log.isDebugEnabled())
                log.debug("Removed swap entry [entry=" + this + ']');
        }
    }

    /**
     * @param tx Transaction.
     * @param key Key.
     * @param reload flag.
     * @param subjId Subject ID.
     * @param taskName Task name.
     * @return Read value.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable protected Object readThrough(@Nullable IgniteInternalTx tx, KeyCacheObject key, boolean reload, UUID subjId,
        String taskName) throws IgniteCheckedException {
        return cctx.store().loadFromStore(tx, key);
    }

    /** {@inheritDoc} */
    @Nullable @Override public final CacheObject innerGet(@Nullable IgniteInternalTx tx,
        boolean readSwap,
        boolean readThrough,
        boolean failFast,
        boolean unmarshal,
        boolean updateMetrics,
        boolean evt,
        boolean tmp,
        UUID subjId,
        Object transformClo,
        String taskName,
        @Nullable IgniteCacheExpiryPolicy expirePlc)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        cctx.denyOnFlag(LOCAL);

        return innerGet0(tx,
            readSwap,
            readThrough,
            evt,
            unmarshal,
            updateMetrics,
            tmp,
            subjId,
            transformClo,
            taskName,
            expirePlc);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked", "RedundantTypeArguments", "TooBroadScope"})
    private CacheObject innerGet0(IgniteInternalTx tx,
        boolean readSwap,
        boolean readThrough,
        boolean evt,
        boolean unmarshal,
        boolean updateMetrics,
        boolean tmp,
        UUID subjId,
        Object transformClo,
        String taskName,
        @Nullable IgniteCacheExpiryPolicy expiryPlc)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        // Disable read-through if there is no store.
        if (readThrough && !cctx.readThrough())
            readThrough = false;

        GridCacheMvccCandidate owner;

        CacheObject old;
        CacheObject ret = null;

        GridCacheVersion startVer;

        boolean expired = false;

        CacheObject expiredVal = null;

        boolean hasOldBytes;

        synchronized (this) {
            checkObsolete();

            // Cache version for optimistic check.
            startVer = ver;

            GridCacheMvcc mvcc = mvccExtras();

            owner = mvcc == null ? null : mvcc.anyOwner();

            double delta;

            long expireTime = expireTimeExtras();

            if (expireTime > 0) {
                delta = expireTime - U.currentTimeMillis();

                if (log.isDebugEnabled())
                    log.debug("Checked expiration time for entry [timeLeft=" + delta + ", entry=" + this + ']');

                if (delta <= 0)
                    expired = true;
            }

            CacheObject val = this.val;

            hasOldBytes = valPtr != 0;

            if ((unmarshal || isOffHeapValuesOnly()) && !expired && val == null && hasOldBytes)
                val = rawGetOrUnmarshalUnlocked(tmp);

            boolean valid = valid(tx != null ? tx.topologyVersion() : cctx.affinity().affinityTopologyVersion());

            // Attempt to load from swap.
            if (val == null && !hasOldBytes && readSwap) {
                // Only promote when loading initial state.
                if (isNew() || !valid) {
                    // If this entry is already expired (expiration time was too low),
                    // we simply remove from swap and clear index.
                    if (expired) {
                        releaseSwap();

                        // Previous value is guaranteed to be null
                        clearIndex(null);
                    }
                    else {
                        // Read and remove swap entry.
                        if (tmp) {
                            unswap(false, false);

                            val = rawGetOrUnmarshalUnlocked(true);
                        }
                        else
                            val = unswap();

                        // Recalculate expiration after swap read.
                        if (expireTime > 0) {
                            delta = expireTime - U.currentTimeMillis();

                            if (log.isDebugEnabled())
                                log.debug("Checked expiration time for entry [timeLeft=" + delta +
                                    ", entry=" + this + ']');

                            if (delta <= 0)
                                expired = true;
                        }
                    }
                }
            }

            old = expired || !valid ? null : val;

            if (expired) {
                expiredVal = val;

                value(null);
            }

            if (old == null && !hasOldBytes) {
                if (updateMetrics && cctx.cache().configuration().isStatisticsEnabled())
                    cctx.cache().metrics0().onRead(false);
            }
            else {
                if (updateMetrics && cctx.cache().configuration().isStatisticsEnabled())
                    cctx.cache().metrics0().onRead(true);

                // Set retVal here for event notification.
                ret = old;
            }

            if (evt && expired) {
                if (cctx.events().isRecordable(EVT_CACHE_OBJECT_EXPIRED)) {
                    cctx.events().addEvent(partition(),
                        key,
                        tx,
                        owner,
                        EVT_CACHE_OBJECT_EXPIRED,
                        null,
                        false,
                        expiredVal,
                        expiredVal != null || hasOldBytes,
                        subjId,
                        null,
                        taskName);
                }

                cctx.continuousQueries().onEntryExpired(this, key, expiredVal);

                // No more notifications.
                evt = false;
            }

            if (evt && !expired && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                cctx.events().addEvent(partition(), key, tx, owner, EVT_CACHE_OBJECT_READ, ret, ret != null, old,
                    hasOldBytes || old != null, subjId,
                    transformClo != null ? transformClo.getClass().getName() : null, taskName);

                // No more notifications.
                evt = false;
            }

            if (ret != null && expiryPlc != null)
                updateTtl(expiryPlc);
        }

        if (ret != null)
            // If return value is consistent, then done.
            return ret;

        boolean loadedFromStore = false;

        if (ret == null && readThrough) {
            IgniteInternalTx tx0 = null;

            if (tx != null && tx.local()) {
                if (cctx.isReplicated() || cctx.isColocated() || tx.near())
                    tx0 = tx;
                else if (tx.dht()) {
                    GridCacheVersion ver = tx.nearXidVersion();

                    tx0 = cctx.dht().near().context().tm().tx(ver);
                }
            }

            Object storeVal = readThrough(tx0, key, false, subjId, taskName);

            if (storeVal != null)
                ret = cctx.toCacheObject(storeVal);

            loadedFromStore = true;
        }

        synchronized (this) {
            long ttl = ttlExtras();

            // If version matched, set value.
            if (startVer.equals(ver)) {
                if (ret != null) {
                    // Detach value before index update.
                    ret = cctx.kernalContext().portable().prepareForCache(ret, cctx);

                    GridCacheVersion nextVer = nextVersion();

                    CacheObject prevVal = rawGetOrUnmarshalUnlocked(false);

                    long expTime = CU.toExpireTime(ttl);

                    if (loadedFromStore)
                        // Update indexes before actual write to entry.
                        updateIndex(ret, expTime, nextVer, prevVal);

                    boolean hadValPtr = valPtr != 0;

                    // Don't change version for read-through.
                    update(ret, expTime, ttl, nextVer);

                    if (hadValPtr && cctx.offheapTiered())
                        cctx.swap().removeOffheap(key);

                    if (cctx.deferredDelete() && deletedUnlocked() && !isInternal() && !detached())
                        deletedUnlocked(false);
                }

                if (evt && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ))
                    cctx.events().addEvent(partition(), key, tx, owner, EVT_CACHE_OBJECT_READ, ret, ret != null,
                        old, hasOldBytes, subjId, transformClo != null ? transformClo.getClass().getName() : null,
                        taskName);
            }
        }

        return ret;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked", "TooBroadScope"})
    @Nullable @Override public final CacheObject innerReload()
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        cctx.denyOnFlag(READ);

        CU.checkStore(cctx);

        GridCacheVersion startVer;

        boolean wasNew;

        synchronized (this) {
            checkObsolete();

            // Cache version for optimistic check.
            startVer = ver;

            wasNew = isNew();
        }

        String taskName = cctx.kernalContext().job().currentTaskName();

        // Check before load.
        CacheObject ret = cctx.toCacheObject(readThrough(null, key, true, cctx.localNodeId(), taskName));

        boolean touch = false;

        try {
            synchronized (this) {
                long ttl = ttlExtras();

                // Generate new version.
                GridCacheVersion nextVer = cctx.versions().nextForLoad(ver);

                // If entry was loaded during read step.
                if (wasNew && !isNew())
                    // Map size was updated on entry creation.
                    return ret;

                // If version matched, set value.
                if (startVer.equals(ver)) {
                    releaseSwap();

                    CacheObject old = rawGetOrUnmarshalUnlocked(false);

                    long expTime = CU.toExpireTime(ttl);

                    // Detach value before index update.
                    ret = cctx.kernalContext().portable().prepareForCache(ret, cctx);

                    // Update indexes.
                    if (ret != null) {
                        updateIndex(ret, expTime, nextVer, old);

                        if (cctx.deferredDelete() && !isInternal() && !detached() && deletedUnlocked())
                            deletedUnlocked(false);
                    }
                    else {
                        clearIndex(old);

                        if (cctx.deferredDelete() && !isInternal() && !detached() && !deletedUnlocked())
                            deletedUnlocked(true);
                    }

                    update(ret, expTime, ttl, nextVer);

                    touch = true;

                    // If value was set - return, otherwise try again.
                    return ret;
                }
            }

            touch = true;

            return ret;
        }
        finally {
            if (touch)
                cctx.evicts().touch(this, cctx.affinity().affinityTopologyVersion());
        }
    }

    /**
     * @param nodeId Node ID.
     */
    protected void recordNodeId(UUID nodeId) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public final GridCacheUpdateTxResult innerSet(
        @Nullable IgniteInternalTx tx,
        UUID evtNodeId,
        UUID affNodeId,
        CacheObject val,
        boolean writeThrough,
        boolean retval,
        long ttl,
        boolean evt,
        boolean metrics,
        long topVer,
        CacheEntryPredicate[] filter,
        GridDrType drType,
        long drExpireTime,
        @Nullable GridCacheVersion explicitVer,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException {
        CacheObject old;

        boolean valid = valid(tx != null ? tx.topologyVersion() : topVer);

        // Lock should be held by now.
        if (!cctx.isAll(this, filter))
            return new GridCacheUpdateTxResult(false, null);

        final GridCacheVersion newVer;

        boolean intercept = cctx.config().getInterceptor() != null;

        Object key0 = null;
        Object val0 = null;

        synchronized (this) {
            checkObsolete();

            if (cctx.kernalContext().config().isCacheSanityCheckEnabled()) {
                if (tx != null && tx.groupLock())
                    groupLockSanityCheck(tx);
                else
                    assert tx == null || (!tx.local() && tx.onePhaseCommit()) || tx.ownsLock(this) :
                        "Transaction does not own lock for update [entry=" + this + ", tx=" + tx + ']';
            }

            // Load and remove from swap if it is new.
            boolean startVer = isStartVersion();

            if (startVer)
                unswap(true, retval);

            newVer = explicitVer != null ? explicitVer : tx == null ?
                nextVersion() : tx.writeVersion();

            assert newVer != null : "Failed to get write version for tx: " + tx;

            old = (retval || intercept) ? rawGetOrUnmarshalUnlocked(!retval) : this.val;

            if (intercept) {
                val0 = CU.value(val, cctx, false);

                CacheLazyEntry e = new CacheLazyEntry(cctx, key, old);

                Object interceptorVal = cctx.config().getInterceptor().onBeforePut(new CacheLazyEntry(cctx, key, old),
                    val0);

                key0 = e.key();

                if (interceptorVal == null)
                    return new GridCacheUpdateTxResult(false, (CacheObject)cctx.unwrapTemporary(old));
                else if (interceptorVal != val0)
                    val0 = cctx.unwrapTemporary(interceptorVal);

                    val = cctx.toCacheObject(val0);
            }

            // Determine new ttl and expire time.
            long expireTime;

            if (drExpireTime >= 0) {
                assert ttl >= 0 : ttl;

                expireTime = drExpireTime;
            }
            else {
                if (ttl == -1L) {
                    ttl = ttlExtras();
                    expireTime = expireTimeExtras();
                }
                else
                    expireTime = CU.toExpireTime(ttl);
            }

            assert ttl >= 0 : ttl;
            assert expireTime >= 0 : expireTime;

            // Detach value before index update.
            val = cctx.kernalContext().portable().prepareForCache(val, cctx);

            // Update index inside synchronization since it can be updated
            // in load methods without actually holding entry lock.
            if (val != null) {
                updateIndex(val, expireTime, newVer, old);

                if (cctx.deferredDelete() && deletedUnlocked() && !isInternal() && !detached())
                    deletedUnlocked(false);
            }

            update(val, expireTime, ttl, newVer);

            drReplicate(drType, val, newVer);

            recordNodeId(affNodeId);

            if (metrics && cctx.cache().configuration().isStatisticsEnabled())
                cctx.cache().metrics0().onWrite();

            if (evt && newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_PUT)) {
                CacheObject evtOld = cctx.unwrapTemporary(old);

                cctx.events().addEvent(partition(),
                    key,
                    evtNodeId,
                    tx == null ? null : tx.xid(),
                    newVer,
                    EVT_CACHE_OBJECT_PUT,
                    val,
                    val != null,
                    evtOld,
                    evtOld != null || hasValueUnlocked(),
                    subjId, null, taskName);
            }

            if (cctx.isLocal() || cctx.isReplicated() || (tx != null && tx.local() && !isNear()))
                cctx.continuousQueries().onEntryUpdated(this, key, val, old, false);

            cctx.dataStructures().onEntryUpdated(key, false);
        }

        if (log.isDebugEnabled())
            log.debug("Updated cache entry [val=" + val + ", old=" + old + ", entry=" + this + ']');

        // Persist outside of synchronization. The correctness of the
        // value will be handled by current transaction.
        if (writeThrough)
            cctx.store().putToStore(tx, key, val, newVer);

        if (intercept)
            cctx.config().getInterceptor().onAfterPut(new CacheLazyEntry(cctx, key, key0, val, val0));

        return valid ? new GridCacheUpdateTxResult(true, retval ? old : null) :
            new GridCacheUpdateTxResult(false, null);
    }

    /** {@inheritDoc} */
    @Override public final GridCacheUpdateTxResult innerRemove(
        @Nullable IgniteInternalTx tx,
        UUID evtNodeId,
        UUID affNodeId,
        boolean writeThrough,
        boolean retval,
        boolean evt,
        boolean metrics,
        long topVer,
        CacheEntryPredicate[] filter,
        GridDrType drType,
        @Nullable GridCacheVersion explicitVer,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException {
        assert cctx.transactional();

        CacheObject old;

        GridCacheVersion newVer;

        boolean valid = valid(tx != null ? tx.topologyVersion() : topVer);

        // Lock should be held by now.
        if (!cctx.isAll(this, filter))
            return new GridCacheUpdateTxResult(false, null);

        GridCacheVersion obsoleteVer = null;

        boolean intercept = cctx.config().getInterceptor() != null;

        IgniteBiTuple<Boolean, Object> interceptRes = null;

        Cache.Entry entry0 = null;

        synchronized (this) {
            checkObsolete();

            if (tx != null && tx.groupLock() && cctx.kernalContext().config().isCacheSanityCheckEnabled())
                groupLockSanityCheck(tx);
            else
                assert tx == null || (!tx.local() && tx.onePhaseCommit()) || tx.ownsLock(this) :
                    "Transaction does not own lock for remove[entry=" + this + ", tx=" + tx + ']';

            boolean startVer = isStartVersion();

            if (startVer) {
                // Release swap.
                releaseSwap();
            }

            newVer = explicitVer != null ? explicitVer : tx == null ? nextVersion() : tx.writeVersion();

            old = (retval || intercept) ? rawGetOrUnmarshalUnlocked(!retval) : val;

            if (intercept) {
                entry0 = new CacheLazyEntry(cctx, key, old);

                interceptRes = cctx.config().getInterceptor().onBeforeRemove(entry0);

                if (cctx.cancelRemove(interceptRes)) {
                    CacheObject ret = cctx.toCacheObject(cctx.unwrapTemporary(interceptRes.get2()));

                    return new GridCacheUpdateTxResult(false, ret);
                }
            }

            if (old == null)
                old = saveValueForIndexUnlocked();

            // Clear indexes inside of synchronization since indexes
            // can be updated without actually holding entry lock.
            clearIndex(old);

            boolean hadValPtr = valPtr != 0;

            update(null, 0, 0, newVer);

            if (cctx.offheapTiered() && hadValPtr) {
                boolean rmv = cctx.swap().removeOffheap(key);

                assert rmv;
            }

            if (cctx.deferredDelete() && !detached() && !isInternal()) {
                if (!deletedUnlocked()) {
                    deletedUnlocked(true);

                    if (tx != null) {
                        GridCacheMvcc mvcc = mvccExtras();

                        if (mvcc == null || mvcc.isEmpty(tx.xidVersion()))
                            clearReaders();
                        else
                            clearReader(tx.originatingNodeId());
                    }
                }
            }

            drReplicate(drType, null, newVer);

            if (metrics && cctx.cache().configuration().isStatisticsEnabled())
                cctx.cache().metrics0().onRemove();

            if (tx == null)
                obsoleteVer = newVer;
            else {
                // Only delete entry if the lock is not explicit.
                if (tx.groupLock() || lockedBy(tx.xidVersion()))
                    obsoleteVer = tx.xidVersion();
                else if (log.isDebugEnabled())
                    log.debug("Obsolete version was not set because lock was explicit: " + this);
            }

            if (evt && newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED)) {
                CacheObject evtOld = cctx.unwrapTemporary(old);

                cctx.events().addEvent(partition(),
                    key,
                    evtNodeId,
                    tx == null ? null : tx.xid(), newVer,
                    EVT_CACHE_OBJECT_REMOVED,
                    null,
                    false,
                    evtOld,
                    evtOld != null || hasValueUnlocked(),
                    subjId,
                    null,
                    taskName);
            }

                if (cctx.isLocal() || cctx.isReplicated() || (tx != null && tx.local() && !isNear()))
                    cctx.continuousQueries().onEntryUpdated(this, key, null, old, false);

            cctx.dataStructures().onEntryUpdated(key, true);
        }

        // Persist outside of synchronization. The correctness of the
        // value will be handled by current transaction.
        if (writeThrough)
            cctx.store().removeFromStore(tx, key);

        if (!cctx.deferredDelete()) {
            boolean marked = false;

            synchronized (this) {
                // If entry is still removed.
                if (newVer == ver) {
                    if (obsoleteVer == null || !(marked = markObsolete0(obsoleteVer, true))) {
                        if (log.isDebugEnabled())
                            log.debug("Entry could not be marked obsolete (it is still used): " + this);
                    }
                    else {
                        recordNodeId(affNodeId);

                        // If entry was not marked obsolete, then removed lock
                        // will be registered whenever removeLock is called.
                        cctx.mvcc().addRemoved(cctx, obsoleteVer);

                        if (log.isDebugEnabled())
                            log.debug("Entry was marked obsolete: " + this);
                    }
                }
            }

            if (marked)
                onMarkedObsolete();
        }

        if (intercept)
            cctx.config().getInterceptor().onAfterRemove(entry0);

        if (valid) {
            CacheObject ret;

            if (interceptRes != null)
                ret = cctx.toCacheObject(cctx.unwrapTemporary(interceptRes.get2()));
            else
                ret = old;

            return new GridCacheUpdateTxResult(true, ret);
        }
        else
            return new GridCacheUpdateTxResult(false, null);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridTuple3<Boolean, Object, EntryProcessorResult<Object>> innerUpdateLocal(
        GridCacheVersion ver,
        GridCacheOperation op,
        @Nullable Object writeObj,
        @Nullable Object[] invokeArgs,
        boolean writeThrough,
        boolean retval,
        @Nullable ExpiryPolicy expiryPlc,
        boolean evt,
        boolean metrics,
        @Nullable CacheEntryPredicate[] filter,
        boolean intercept,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException {
        assert cctx.isLocal() && cctx.atomic();

        CacheObject old;

        boolean res = true;

        IgniteBiTuple<Boolean, ?> interceptorRes = null;

        EntryProcessorResult<Object> invokeRes = null;

        synchronized (this) {
            boolean needVal = retval || intercept || op == GridCacheOperation.TRANSFORM || !F.isEmpty(filter);

            checkObsolete();

            // Load and remove from swap if it is new.
            if (isNew())
                unswap(true, retval);

            // Possibly get old value form store.
            old = needVal ? rawGetOrUnmarshalUnlocked(!retval) : val;

            boolean readThrough = false;

            Object old0 = null;

            if (needVal && old == null &&
                (cctx.readThrough() && (op == GridCacheOperation.TRANSFORM || cctx.loadPreviousValue()))) {
                    old0 = readThrough(null, key, false, subjId, taskName);

                old = cctx.toCacheObject(old0);

                long ttl = CU.TTL_ETERNAL;
                long expireTime = CU.EXPIRE_TIME_ETERNAL;

                if (expiryPlc != null && old != null) {
                    ttl = CU.toTtl(expiryPlc.getExpiryForCreation());

                    if (ttl == CU.TTL_ZERO) {
                        ttl = CU.TTL_MINIMUM;
                        expireTime = CU.expireTimeInPast();
                    }
                    else if (ttl == CU.TTL_NOT_CHANGED)
                        ttl = CU.TTL_ETERNAL;
                    else
                        expireTime = CU.toExpireTime(ttl);
                }

                // Detach value before index update.
                old = cctx.kernalContext().portable().prepareForCache(old, cctx);

                if (old != null)
                    updateIndex(old, expireTime, ver, null);
                else
                    clearIndex(null);

                update(old, expireTime, ttl, ver);
            }

            // Apply metrics.
            if (metrics && cctx.cache().configuration().isStatisticsEnabled() && needVal) {
                // PutIfAbsent methods mustn't update hit/miss statistics
                if (op != GridCacheOperation.UPDATE || F.isEmpty(filter) || !cctx.putIfAbsentFilter(filter))
                    cctx.cache().metrics0().onRead(old != null);
            }

            // Check filter inside of synchronization.
            if (!F.isEmpty(filter)) {
                boolean pass = cctx.isAll(this, filter);

                if (!pass) {
                    if (expiryPlc != null && !readThrough && !cctx.putIfAbsentFilter(filter) && hasValueUnlocked())
                        updateTtl(expiryPlc);

                    return new T3<>(false, retval ? CU.value(old, cctx, false) : null, null);
                }
            }

            String transformCloClsName = null;

            CacheObject updated;

            Object key0 = null;
            Object updated0 = null;

            // Calculate new value.
            if (op == GridCacheOperation.TRANSFORM) {
                transformCloClsName = writeObj.getClass().getName();

                EntryProcessor<Object, Object, ?> entryProcessor = (EntryProcessor<Object, Object, ?>)writeObj;

                assert entryProcessor != null;

                CacheInvokeEntry<Object, Object> entry = new CacheInvokeEntry<>(cctx, key, old);

                try {
                    Object computed = entryProcessor.process(entry, invokeArgs);

                    if (entry.modified()) {
                        updated0 = cctx.unwrapTemporary(entry.getValue());

                        updated = cctx.toCacheObject(updated0);
                    }
                    else
                        updated = old;

                    key0 = entry.key();

                    invokeRes = computed != null ? new CacheInvokeResult<>(cctx.unwrapTemporary(computed)) : null;
                }
                catch (Exception e) {
                    updated = old;

                    invokeRes = new CacheInvokeResult<>(e);
                }

                if (!entry.modified()) {
                    if (expiryPlc != null && !readThrough && hasValueUnlocked())
                        updateTtl(expiryPlc);

                    return new GridTuple3<>(false, null, invokeRes);
                }
            }
            else
                updated = (CacheObject)writeObj;

            op = updated == null ? GridCacheOperation.DELETE : GridCacheOperation.UPDATE;

            if (intercept) {
                CacheLazyEntry e;

                if (op == GridCacheOperation.UPDATE) {
                    updated0 = value(updated0, updated, false);

                    e = new CacheLazyEntry(cctx, key, key0, old, old0);

                    Object interceptorVal = cctx.config().getInterceptor().onBeforePut(e, updated0);

                    if (interceptorVal == null)
                        return new GridTuple3<>(false, cctx.unwrapTemporary(value(old0, old, false)), invokeRes);
                    else {
                        updated0 = cctx.unwrapTemporary(interceptorVal);

                        updated = cctx.toCacheObject(updated0);
                    }
                }
                else {
                    e = new CacheLazyEntry(cctx, key, key0, old, old0);

                    interceptorRes = cctx.config().getInterceptor().onBeforeRemove(e);

                    if (cctx.cancelRemove(interceptorRes))
                        return new GridTuple3<>(false, cctx.unwrapTemporary(interceptorRes.get2()), invokeRes);
                }

                key0 = e.key();
                old0 = e.value();
            }

            boolean hadVal = hasValueUnlocked();

            long ttl = CU.TTL_ETERNAL;
            long expireTime = CU.EXPIRE_TIME_ETERNAL;

            if (op == GridCacheOperation.UPDATE) {
                if (expiryPlc != null) {
                    ttl = CU.toTtl(hadVal ? expiryPlc.getExpiryForUpdate() : expiryPlc.getExpiryForCreation());

                    if (ttl == CU.TTL_NOT_CHANGED) {
                        ttl = ttlExtras();
                        expireTime = expireTimeExtras();
                    }
                    else if (ttl != CU.TTL_ZERO)
                        expireTime = CU.toExpireTime(ttl);
                }
                else {
                    ttl = ttlExtras();
                    expireTime = expireTimeExtras();
                }
            }

            if (ttl == CU.TTL_ZERO)
                op = GridCacheOperation.DELETE;

            // Try write-through.
            if (op == GridCacheOperation.UPDATE) {
                // Detach value before index update.
                updated = cctx.kernalContext().portable().prepareForCache(updated, cctx);

                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().putToStore(null, key, updated, ver);

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                updateIndex(updated, expireTime, ver, old);

                assert ttl != CU.TTL_ZERO;

                update(updated, expireTime, ttl, ver);

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformCloClsName != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                        evtOld = cctx.unwrapTemporary(old);

                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null,
                            (GridCacheVersion)null, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformCloClsName, taskName);
                    }

                    if (cctx.events().isRecordable(EVT_CACHE_OBJECT_PUT)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(old);

                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null,
                            (GridCacheVersion)null, EVT_CACHE_OBJECT_PUT, updated, updated != null, evtOld,
                            evtOld != null || hadVal, subjId, null, taskName);
                    }
                }
            }
            else {
                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().removeFromStore(null, key);

                boolean hasValPtr = valPtr != 0;

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                clearIndex(old);

                update(null, CU.TTL_ETERNAL, CU.EXPIRE_TIME_ETERNAL, ver);

                if (cctx.offheapTiered() && hasValPtr) {
                    boolean rmv = cctx.swap().removeOffheap(key);

                    assert rmv;
                }

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformCloClsName != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ))
                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null,
                            (GridCacheVersion)null, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformCloClsName, taskName);

                    if (cctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(old);

                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null, (GridCacheVersion) null,
                            EVT_CACHE_OBJECT_REMOVED, null, false, evtOld, evtOld != null || hadVal, subjId, null,
                            taskName);
                    }
                }

                res = hadVal;
            }

            if (res)
                updateMetrics(op, metrics);

            cctx.continuousQueries().onEntryUpdated(this, key, val, old, false);

            cctx.dataStructures().onEntryUpdated(key, op == GridCacheOperation.DELETE);

            if (intercept) {
                if (op == GridCacheOperation.UPDATE)
                    cctx.config().getInterceptor().onAfterPut(new CacheLazyEntry(cctx, key, key0, updated, updated0));
                else
                    cctx.config().getInterceptor().onAfterRemove(new CacheLazyEntry(cctx, key, key0, old, old0));
            }
        }

        return new GridTuple3<>(res,
            cctx.unwrapTemporary(interceptorRes != null ? interceptorRes.get2() : CU.value(old, cctx, false)),
            invokeRes);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridCacheUpdateAtomicResult innerUpdate(
        GridCacheVersion newVer,
        UUID evtNodeId,
        UUID affNodeId,
        GridCacheOperation op,
        @Nullable Object writeObj,
        @Nullable Object[] invokeArgs,
        boolean writeThrough,
        boolean retval,
        @Nullable IgniteCacheExpiryPolicy expiryPlc,
        boolean evt,
        boolean metrics,
        boolean primary,
        boolean verCheck,
        @Nullable CacheEntryPredicate[] filter,
        GridDrType drType,
        long explicitTtl,
        long explicitExpireTime,
        @Nullable GridCacheVersion conflictVer,
        boolean conflictResolve,
        boolean intercept,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException, GridClosureException {
        assert cctx.atomic();

        boolean res = true;

        CacheObject oldVal;
        CacheObject updated;

        GridCacheVersion enqueueVer = null;

        GridCacheVersionConflictContext<?, ?> conflictCtx = null;

        CacheInvokeDirectResult invokeRes = null;

        // System TTL/ET which may have special values.
        long newSysTtl;
        long newSysExpireTime;

        // TTL/ET which will be passed to entry on update.
        long newTtl;
        long newExpireTime;

        synchronized (this) {
            boolean needVal = intercept || retval || op == GridCacheOperation.TRANSFORM || !F.isEmptyOrNulls(filter);

            checkObsolete();

            // Load and remove from swap if it is new.
            if (isNew())
                unswap(true, retval);

            Object transformClo = null;

            // Request-level conflict resolution is needed, i.e. we do not know who will win in advance.
            if (conflictResolve) {
                GridCacheVersion oldConflictVer = version().conflictVersion();

                // Cache is conflict-enabled.
                if (cctx.conflictNeedResolve()) {
                    // Get new value, optionally unmarshalling and/or transforming it.
                    Object writeObj0;

                    if (op == GridCacheOperation.TRANSFORM) {
                        transformClo = writeObj;

                        // TODO IGNITE-51
                        writeObj0 = ((IgniteClosure)writeObj).apply(rawGetOrUnmarshalUnlocked(true));
                    }
                    else
                        writeObj0 = CU.value((CacheObject)writeObj, cctx, false);

                    GridTuple3<Long, Long, Boolean> expiration = ttlAndExpireTime(expiryPlc,
                        explicitTtl,
                        explicitExpireTime);

                    // Prepare old and new entries for conflict resolution.
                    GridCacheVersionedEntryEx oldEntry = versionedEntry();
                    GridCacheVersionedEntryEx newEntry = new GridCachePlainVersionedEntry<>(
                        oldEntry.key(),
                        writeObj0,
                        expiration.get1(),
                        expiration.get2(),
                        conflictVer != null ? conflictVer : newVer);

                    // Resolve conflict.
                    conflictCtx = cctx.conflictResolve(oldEntry, newEntry, verCheck);

                    assert conflictCtx != null;

                    // Use old value?
                    if (conflictCtx.isUseOld()) {
                        GridCacheVersion newConflictVer = conflictVer != null ? conflictVer : newVer;

                        // Handle special case with atomic comparator.
                        if (!isNew() &&                                                           // Not initial value,
                            verCheck &&                                                           // and atomic version check,
                            oldConflictVer.dataCenterId() == newConflictVer.dataCenterId() &&     // and data centers are equal,
                            ATOMIC_VER_COMPARATOR.compare(oldConflictVer, newConflictVer) == 0 && // and both versions are equal,
                            cctx.writeThrough() &&                                                // and store is enabled,
                            primary)                                                              // and we are primary.
                        {
                            CacheObject val = rawGetOrUnmarshalUnlocked(false);

                            if (val == null) {
                                assert deletedUnlocked();

                                cctx.store().removeFromStore(null, key());
                            }
                            else
                                cctx.store().putToStore(null, key(), val, ver);
                        }

                        return new GridCacheUpdateAtomicResult(false,
                            retval ? rawGetOrUnmarshalUnlocked(false) : null,
                            null,
                            invokeRes,
                            CU.TTL_ETERNAL,
                            CU.EXPIRE_TIME_ETERNAL,
                            null,
                            null,
                            false);
                    }
                    // Will update something.
                    else {
                        // Merge is a local update which override passed value bytes.
                        if (conflictCtx.isMerge()) {
                            writeObj = cctx.toCacheObject(conflictCtx.mergeValue());

                            conflictVer = null;
                        }
                        else
                            assert conflictCtx.isUseNew();

                        // Update value is known at this point, so update operation type.
                        op = writeObj != null ? GridCacheOperation.UPDATE : GridCacheOperation.DELETE;
                    }
                }
                else
                    // Nullify conflict version on this update, so that we will use regular version during next updates.
                    conflictVer = null;
            }

            // Perform version check only in case there was no explicit conflict resolution.
            if (conflictCtx == null) {
                if (verCheck) {
                    if (!isNew() && ATOMIC_VER_COMPARATOR.compare(ver, newVer) >= 0) {
                        if (ATOMIC_VER_COMPARATOR.compare(ver, newVer) == 0 && cctx.writeThrough() && primary) {
                            if (log.isDebugEnabled())
                                log.debug("Received entry update with same version as current (will update store) " +
                                    "[entry=" + this + ", newVer=" + newVer + ']');

                            CacheObject val = rawGetOrUnmarshalUnlocked(false);

                            if (val == null) {
                                assert deletedUnlocked();

                                cctx.store().removeFromStore(null, key());
                            }
                            else
                                cctx.store().putToStore(null, key(), val, ver);
                        }
                        else {
                            if (log.isDebugEnabled())
                                log.debug("Received entry update with smaller version than current (will ignore) " +
                                    "[entry=" + this + ", newVer=" + newVer + ']');
                        }

                        return new GridCacheUpdateAtomicResult(false,
                            retval ? rawGetOrUnmarshalUnlocked(false) : null,
                            null,
                            invokeRes,
                            CU.TTL_ETERNAL,
                            CU.EXPIRE_TIME_ETERNAL,
                            null,
                            null,
                            false);
                    }
                }
                else
                    assert isNew() || ATOMIC_VER_COMPARATOR.compare(ver, newVer) <= 0 :
                        "Invalid version for inner update [entry=" + this + ", newVer=" + newVer + ']';
            }

            // Prepare old value and value bytes.
            oldVal = needVal ? rawGetOrUnmarshalUnlocked(!retval) : val;

            // Possibly read value from store.
            boolean readThrough = false;

            Object old0 = null;
            Object updated0 = null;

            if (needVal && oldVal == null && (cctx.readThrough() &&
                (op == GridCacheOperation.TRANSFORM || cctx.loadPreviousValue()))) {
                old0 = readThrough(null, key, false, subjId, taskName);

                oldVal = cctx.toCacheObject(old0);

                readThrough = true;

                // Detach value before index update.
                oldVal = cctx.kernalContext().portable().prepareForCache(oldVal, cctx);

                // Calculate initial TTL and expire time.
                long initTtl;
                long initExpireTime;

                if (expiryPlc != null && oldVal != null) {
                    IgniteBiTuple<Long, Long> initTtlAndExpireTime = initialTtlAndExpireTime(expiryPlc);

                    initTtl = initTtlAndExpireTime.get1();
                    initExpireTime = initTtlAndExpireTime.get2();
                }
                else {
                    initTtl = CU.TTL_ETERNAL;
                    initExpireTime = CU.EXPIRE_TIME_ETERNAL;
                }

                if (oldVal != null)
                    updateIndex(oldVal, initExpireTime, ver, null);
                else
                    clearIndex(null);

                update(oldVal, initExpireTime, initTtl, ver);

                if (deletedUnlocked() && oldVal != null && !isInternal())
                    deletedUnlocked(false);
            }

            // Apply metrics.
            if (metrics && cctx.cache().configuration().isStatisticsEnabled() && needVal) {
                // PutIfAbsent methods mustn't update hit/miss statistics
                if (op != GridCacheOperation.UPDATE || F.isEmpty(filter) || !cctx.putIfAbsentFilter(filter))
                    cctx.cache().metrics0().onRead(oldVal != null);
            }

            // Check filter inside of synchronization.
            if (!F.isEmptyOrNulls(filter)) {
                boolean pass = cctx.isAll(this, filter);

                if (!pass) {
                    if (expiryPlc != null && !readThrough && hasValueUnlocked() && !cctx.putIfAbsentFilter(filter))
                        updateTtl(expiryPlc);

                    return new GridCacheUpdateAtomicResult(false,
                        retval ? oldVal : null,
                        null,
                        invokeRes,
                        CU.TTL_ETERNAL,
                        CU.EXPIRE_TIME_ETERNAL,
                        null,
                        null,
                        false);
                }
            }

            Object key0 = null;

            // Calculate new value in case we met transform.
            if (op == GridCacheOperation.TRANSFORM) {
                assert conflictCtx == null : "Cannot be TRANSFORM here if conflict resolution was performed earlier.";

                transformClo = writeObj;

                EntryProcessor<Object, Object, ?> entryProcessor = (EntryProcessor<Object, Object, ?>)writeObj;

                CacheInvokeEntry<Object, Object> entry = new CacheInvokeEntry(cctx, key, oldVal);

                try {
                    Object computed = entryProcessor.process(entry, invokeArgs);

                    if (entry.modified()) {
                        updated0 = cctx.unwrapTemporary(entry.getValue());
                        updated = cctx.toCacheObject(updated0);
                    }
                    else
                        updated = oldVal;

                    key0 = entry.key();

                    if (computed != null)
                        invokeRes = new CacheInvokeDirectResult(key,
                            cctx.toCacheObject(cctx.unwrapTemporary(computed)));
                }
                catch (Exception e) {
                    invokeRes = new CacheInvokeDirectResult(key, e);

                    updated = oldVal;
                }

                if (!entry.modified()) {
                    if (expiryPlc != null && !readThrough && hasValueUnlocked())
                        updateTtl(expiryPlc);

                    return new GridCacheUpdateAtomicResult(false,
                        retval ? oldVal : null,
                        null,
                        invokeRes,
                        CU.TTL_ETERNAL,
                        CU.EXPIRE_TIME_ETERNAL,
                        null,
                        null,
                        false);
                }
            }
            else
                updated = (CacheObject)writeObj;

            op = updated == null ? GridCacheOperation.DELETE : GridCacheOperation.UPDATE;

            assert op == GridCacheOperation.UPDATE || (op == GridCacheOperation.DELETE && updated == null);

            boolean hadVal = hasValueUnlocked();

            // Incorporate conflict version into new version if needed.
            if (conflictVer != null && conflictVer != newVer)
                newVer = new GridCacheVersionEx(newVer.topologyVersion(),
                    newVer.globalTime(),
                    newVer.order(),
                    newVer.nodeOrder(),
                    newVer.dataCenterId(),
                    conflictVer);

            if (op == GridCacheOperation.UPDATE) {
                // Conflict context is null if there were no explicit conflict resolution.
                if (conflictCtx == null) {
                    // Calculate TTL and expire time for local update.
                    if (explicitTtl != CU.TTL_NOT_CHANGED) {
                        // If conflict existed, expire time must be explicit.
                        assert conflictVer == null || explicitExpireTime != CU.EXPIRE_TIME_CALCULATE;

                        newSysTtl = newTtl = explicitTtl;
                        newSysExpireTime = explicitExpireTime;

                        newExpireTime = explicitExpireTime != CU.EXPIRE_TIME_CALCULATE ?
                            explicitExpireTime : CU.toExpireTime(explicitTtl);
                    }
                    else {
                        newSysTtl = expiryPlc == null ? CU.TTL_NOT_CHANGED :
                            hadVal ? expiryPlc.forUpdate() : expiryPlc.forCreate();

                        if (newSysTtl == CU.TTL_NOT_CHANGED) {
                            newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;
                            newTtl = ttlExtras();
                            newExpireTime = expireTimeExtras();
                        }
                        else if (newSysTtl == CU.TTL_ZERO) {
                            op = GridCacheOperation.DELETE;

                            newSysTtl = CU.TTL_NOT_CHANGED;
                            newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;

                            newTtl = CU.TTL_ETERNAL;
                            newExpireTime = CU.EXPIRE_TIME_ETERNAL;

                            updated = null;
                        }
                        else {
                            newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;
                            newTtl = newSysTtl;
                            newExpireTime = CU.toExpireTime(newTtl);
                        }
                    }
                }
                else {
                    newSysTtl = newTtl = conflictCtx.ttl();
                    newSysExpireTime = newExpireTime = conflictCtx.expireTime();
                }
            }
            else {
                assert op == GridCacheOperation.DELETE;

                newSysTtl = CU.TTL_NOT_CHANGED;
                newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;

                newTtl = CU.TTL_ETERNAL;
                newExpireTime = CU.EXPIRE_TIME_ETERNAL;
            }

            // TTL and expire time must be resolved at this point.
            assert newTtl != CU.TTL_NOT_CHANGED && newTtl != CU.TTL_ZERO && newTtl >= 0;
            assert newExpireTime != CU.EXPIRE_TIME_CALCULATE && newExpireTime >= 0;

            IgniteBiTuple<Boolean, Object> interceptRes = null;

            // Actual update.
            if (op == GridCacheOperation.UPDATE) {
                if (intercept) {
                    updated0 = value(updated0, updated, false);

                    Object interceptorVal = cctx.config().getInterceptor()
                        .onBeforePut(new CacheLazyEntry(cctx, key, key0, oldVal, old0), updated0);

                    if (interceptorVal == null)
                        return new GridCacheUpdateAtomicResult(false,
                            retval ? oldVal : null,
                            null,
                            invokeRes,
                            CU.TTL_ETERNAL,
                            CU.EXPIRE_TIME_ETERNAL,
                            null,
                            null,
                            false);
                    else if (interceptorVal != updated0) {
                        updated0 = cctx.unwrapTemporary(interceptorVal);

                        updated = cctx.toCacheObject(updated0);
                    }
                }

                // Try write-through.
                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().putToStore(null, key, updated, newVer);

                if (!hadVal) {
                    boolean new0 = isNew();

                    assert deletedUnlocked() || new0 || isInternal(): "Invalid entry [entry=" + this + ", locNodeId=" +
                        cctx.localNodeId() + ']';

                    if (!new0 && !isInternal())
                        deletedUnlocked(false);
                }
                else {
                    assert !deletedUnlocked() : "Invalid entry [entry=" + this +
                        ", locNodeId=" + cctx.localNodeId() + ']';

                    // Do not change size.
                }

                updated = cctx.kernalContext().portable().prepareForCache(updated, cctx);

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                updateIndex(updated, newExpireTime, newVer, oldVal);

                update(updated, newExpireTime, newTtl, newVer);

                drReplicate(drType, updated, newVer);

                recordNodeId(affNodeId);

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformClo != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                        evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null,
                            newVer, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformClo.getClass().getName(), taskName);
                    }

                    if (newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_PUT)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null,
                            newVer, EVT_CACHE_OBJECT_PUT, updated, updated != null, evtOld,
                            evtOld != null || hadVal, subjId, null, taskName);
                    }
                }
            }
            else {
                if (intercept) {
                    interceptRes = cctx.config().getInterceptor().onBeforeRemove(new CacheLazyEntry(cctx, key, key0,
                        oldVal, old0));

                    if (cctx.cancelRemove(interceptRes))
                        return new GridCacheUpdateAtomicResult(false,
                            cctx.toCacheObject(cctx.unwrapTemporary(interceptRes.get2())),
                            null,
                            invokeRes,
                            CU.TTL_ETERNAL,
                            CU.EXPIRE_TIME_ETERNAL,
                            null,
                            null,
                            false);
                }

                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().removeFromStore(null, key);

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                clearIndex(oldVal);

                if (hadVal) {
                    assert !deletedUnlocked();

                    if (!isInternal())
                        deletedUnlocked(true);
                }
                else {
                    boolean new0 = isNew();

                    assert deletedUnlocked() || new0 || isInternal() : "Invalid entry [entry=" + this + ", locNodeId=" +
                        cctx.localNodeId() + ']';

                    if (new0) {
                        if (!isInternal())
                            deletedUnlocked(true);
                    }
                }

                enqueueVer = newVer;

                boolean hasValPtr = valPtr != 0;

                // Clear value on backup. Entry will be removed from cache when it got evicted from queue.
                update(null, CU.TTL_ETERNAL, CU.EXPIRE_TIME_ETERNAL, newVer);

                assert newSysTtl == CU.TTL_NOT_CHANGED;
                assert newSysExpireTime == CU.EXPIRE_TIME_CALCULATE;

                if (cctx.offheapTiered() && hasValPtr) {
                    boolean rmv = cctx.swap().removeOffheap(key);

                    assert rmv;
                }

                clearReaders();

                recordNodeId(affNodeId);

                drReplicate(drType, null, newVer);

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformClo != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                        evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null,
                            newVer, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformClo.getClass().getName(), taskName);
                    }

                    if (newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null, newVer,
                            EVT_CACHE_OBJECT_REMOVED, null, false, evtOld, evtOld != null || hadVal,
                            subjId, null, taskName);
                    }
                }

                res = hadVal;
            }

            if (res)
                updateMetrics(op, metrics);

            if (cctx.isReplicated() || primary)
                cctx.continuousQueries().onEntryUpdated(this, key, val, oldVal, false);

            cctx.dataStructures().onEntryUpdated(key, op == GridCacheOperation.DELETE);

            if (intercept) {
                if (op == GridCacheOperation.UPDATE)
                    cctx.config().getInterceptor().onAfterPut(new CacheLazyEntry(cctx, key, key0, updated, updated0));
                else
                    cctx.config().getInterceptor().onAfterRemove(new CacheLazyEntry(cctx, key, key0, oldVal, old0));

                if (interceptRes != null)
                    oldVal = cctx.toCacheObject(cctx.unwrapTemporary(interceptRes.get2()));
            }
        }

        if (log.isDebugEnabled())
            log.debug("Updated cache entry [val=" + val + ", old=" + oldVal + ", entry=" + this + ']');

        return new GridCacheUpdateAtomicResult(res,
            oldVal,
            updated,
            invokeRes,
            newSysTtl,
            newSysExpireTime,
            enqueueVer,
            conflictCtx,
            true);
    }

    /**
     * @param val Value.
     * @param cacheObj Cache object.
     * @param cpy Copy flag.
     * @return Cache object value.
     */
    @Nullable private Object value(@Nullable Object val, @Nullable CacheObject cacheObj, boolean cpy) {
        if (val != null)
            return val;

        return cacheObj != null ? cacheObj.value(cctx.cacheObjectContext(), cpy) : null;
    }

    /**
     * @param expiry Expiration policy.
     * @return Tuple holding initial TTL and expire time with the given expiry.
     */
    private static IgniteBiTuple<Long, Long> initialTtlAndExpireTime(IgniteCacheExpiryPolicy expiry) {
        assert expiry != null;

        long initTtl = expiry.forCreate();
        long initExpireTime;

        if (initTtl == CU.TTL_ZERO) {
            initTtl = CU.TTL_MINIMUM;
            initExpireTime = CU.expireTimeInPast();
        }
        else if (initTtl == CU.TTL_NOT_CHANGED) {
            initTtl = CU.TTL_ETERNAL;
            initExpireTime = CU.EXPIRE_TIME_ETERNAL;
        }
        else
            initExpireTime = CU.toExpireTime(initTtl);

        return F.t(initTtl, initExpireTime);
    }

    /**
     * Get TTL, expire time and remove flag for the given entry, expiration policy and explicit TTL and expire time.
     *
     * @param expiry Expiration policy.
     * @param ttl Explicit TTL.
     * @param expireTime Explicit expire time.
     * @return Result.
     */
    private GridTuple3<Long, Long, Boolean> ttlAndExpireTime(IgniteCacheExpiryPolicy expiry, long ttl, long expireTime)
        throws GridCacheEntryRemovedException {
        boolean rmv = false;

        // 1. If TTL is not changed, then calculate it based on expiry.
        if (ttl == CU.TTL_NOT_CHANGED) {
            if (expiry != null)
                ttl = hasValueUnlocked() ? expiry.forUpdate() : expiry.forCreate();
        }

        // 2. If TTL is zero, then set delete marker.
        if (ttl == CU.TTL_ZERO) {
            rmv = true;

            ttl = CU.TTL_ETERNAL;
        }

        // 3. If TTL is still not changed, then either use old entry TTL or set it to "ETERNAL".
        if (ttl == CU.TTL_NOT_CHANGED) {
            if (isNew())
                ttl = CU.TTL_ETERNAL;
            else {
                ttl = ttlExtras();
                expireTime = expireTimeExtras();
            }
        }

        // 4 If expire time was not set explicitly, then calculate it.
        if (expireTime == CU.EXPIRE_TIME_CALCULATE)
            expireTime = CU.toExpireTime(ttl);

        return F.t(ttl, expireTime, rmv);
    }

    /**
     * Perform DR if needed.
     *
     * @param drType DR type.
     * @param val Value.
     * @param ver Version.
     * @throws IgniteCheckedException In case of exception.
     */
    private void drReplicate(GridDrType drType, @Nullable CacheObject val, GridCacheVersion ver)
        throws IgniteCheckedException {
        if (cctx.isDrEnabled() && drType != DR_NONE && !isInternal())
            cctx.dr().replicate(key, val, rawTtl(), rawExpireTime(), ver.conflictVersion(), drType);
    }

    /**
     * @return {@code true} if entry has readers. It makes sense only for dht entry.
     * @throws GridCacheEntryRemovedException If removed.
     */
    protected boolean hasReaders() throws GridCacheEntryRemovedException {
        return false;
    }

    /**
     *
     */
    protected void clearReaders() {
        // No-op.
    }

    /**
     * @param nodeId Node ID to clear.
     */
    protected void clearReader(UUID nodeId) throws GridCacheEntryRemovedException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean clear(GridCacheVersion ver, boolean readers,
        @Nullable CacheEntryPredicate[] filter) throws IgniteCheckedException {
        cctx.denyOnFlag(READ);

        boolean ret;
        boolean rmv;
        boolean marked;

        while (true) {
            ret = false;
            rmv = false;
            marked = false;

            // For optimistic check.
            GridCacheVersion startVer = null;

            if (!F.isEmptyOrNulls(filter)) {
                synchronized (this) {
                    startVer = this.ver;
                }

                if (!cctx.isAll(this, filter))
                    return false;
            }

            synchronized (this) {
                if (startVer != null && !startVer.equals(this.ver))
                    // Version has changed since filter checking.
                    continue;

                CacheObject val = saveValueForIndexUnlocked();

                try {
                    if ((!hasReaders() || readers)) {
                        // markObsolete will clear the value.
                        if (!(marked = markObsolete0(ver, true))) {
                            if (log.isDebugEnabled())
                                log.debug("Entry could not be marked obsolete (it is still used): " + this);

                            break;
                        }

                        clearReaders();
                    }
                    else {
                        if (log.isDebugEnabled())
                            log.debug("Entry could not be marked obsolete (it still has readers): " + this);

                        break;
                    }
                }
                catch (GridCacheEntryRemovedException ignore) {
                    if (log.isDebugEnabled())
                        log.debug("Got removed entry when clearing (will simply return): " + this);

                    ret = true;

                    break;
                }

                if (log.isDebugEnabled())
                    log.debug("Entry has been marked obsolete: " + this);

                clearIndex(val);

                releaseSwap();

                ret = true;
                rmv = true;

                break;
            }
        }

        if (marked)
            onMarkedObsolete();

        if (rmv)
            cctx.cache().removeEntry(this); // Clear cache.

        return ret;
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheVersion obsoleteVersion() {
        return obsoleteVersionExtras();
    }

    /** {@inheritDoc} */
    @Override public boolean markObsolete(GridCacheVersion ver) {
        boolean obsolete;

        synchronized (this) {
            obsolete = markObsolete0(ver, true);
        }

        if (obsolete)
            onMarkedObsolete();

        return obsolete;
    }

    /** {@inheritDoc} */
    @Override public boolean markObsoleteIfEmpty(@Nullable GridCacheVersion ver) throws IgniteCheckedException {
        boolean obsolete = false;
        boolean deferred = false;

        try {
            synchronized (this) {
                if (obsoleteVersionExtras() != null)
                    return false;

                if (!hasValueUnlocked() || checkExpired()) {
                    if (ver == null)
                        ver = nextVersion();

                    if (cctx.deferredDelete() && !isStartVersion() && !detached() && !isInternal()) {
                        if (!deletedUnlocked()) {
                            update(null, 0L, 0L, ver);

                            deletedUnlocked(true);

                            deferred = true;
                        }
                    }
                    else
                        obsolete = markObsolete0(ver, true);
                }
            }
        }
        finally {
            if (obsolete)
                onMarkedObsolete();

            if (deferred)
                cctx.onDeferredDelete(this, ver);
        }

        return obsolete;
    }

    /** {@inheritDoc} */
    @Override public boolean markObsoleteVersion(GridCacheVersion ver) {
        assert cctx.deferredDelete();

        boolean marked;

        synchronized (this) {
            if (obsoleteVersionExtras() != null)
                return true;

            if (!this.ver.equals(ver))
                return false;

            marked = markObsolete0(ver, true);
        }

        if (marked)
            onMarkedObsolete();

        return marked;
    }

    /**
     * <p>
     * Note that {@link #onMarkedObsolete()} should always be called after this method
     * returns {@code true}.
     *
     * @param ver Version.
     * @param clear {@code True} to clear.
     * @return {@code True} if entry is obsolete, {@code false} if entry is still used by other threads or nodes.
     */
    protected final boolean markObsolete0(GridCacheVersion ver, boolean clear) {
        assert Thread.holdsLock(this);

        GridCacheVersion obsoleteVer = obsoleteVersionExtras();

        if (ver != null) {
            // If already obsolete, then do nothing.
            if (obsoleteVer != null)
                return true;

            GridCacheMvcc mvcc = mvccExtras();

            if (mvcc == null || mvcc.isEmpty(ver)) {
                obsoleteVer = ver;

                obsoleteVersionExtras(obsoleteVer);

                if (clear)
                    value(null);
            }

            return obsoleteVer != null;
        }
        else
            return obsoleteVer != null;
    }

    /** {@inheritDoc} */
    @Override public void onMarkedObsolete() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public final synchronized boolean obsolete() {
        return obsoleteVersionExtras() != null;
    }

    /** {@inheritDoc} */
    @Override public final synchronized boolean obsolete(GridCacheVersion exclude) {
        GridCacheVersion obsoleteVer = obsoleteVersionExtras();

        return obsoleteVer != null && !obsoleteVer.equals(exclude);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean invalidate(@Nullable GridCacheVersion curVer, GridCacheVersion newVer)
        throws IgniteCheckedException {
        assert newVer != null;

        if (curVer == null || ver.equals(curVer)) {
            CacheObject val = saveValueForIndexUnlocked();

            value(null);

            ver = newVer;

            releaseSwap();

            clearIndex(val);

            onInvalidate();
        }

        return obsoleteVersionExtras() != null;
    }

    /**
     * Called when entry invalidated.
     */
    protected void onInvalidate() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean invalidate(@Nullable CacheEntryPredicate[] filter)
        throws GridCacheEntryRemovedException, IgniteCheckedException {
        if (F.isEmptyOrNulls(filter)) {
            synchronized (this) {
                checkObsolete();

                invalidate(null, nextVersion());

                return true;
            }
        }
        else {
            // For optimistic checking.
            GridCacheVersion startVer;

            synchronized (this){
                checkObsolete();

                startVer = ver;
            }

            if (!cctx.isAll(this, filter))
                return false;

            synchronized (this) {
                checkObsolete();

                if (startVer.equals(ver)) {
                    invalidate(null, nextVersion());

                    return true;
                }
            }

            // If version has changed then repeat the process.
            return invalidate(filter);
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean compact(@Nullable CacheEntryPredicate[] filter)
        throws GridCacheEntryRemovedException, IgniteCheckedException {
        // For optimistic checking.
        GridCacheVersion startVer;

        synchronized (this) {
            checkObsolete();

            startVer = ver;
        }

        if (!cctx.isAll(this, filter))
            return false;

        synchronized (this) {
            checkObsolete();

            if (deletedUnlocked())
                return false; // Cannot compact soft-deleted entries.

            if (startVer.equals(ver)) {
                if (hasValueUnlocked() && !checkExpired()) {
// TODO IGNITE-51.
//                    if (!isOffHeapValuesOnly()) {
//                        if (val != null)
//                            valBytes = null;
//                    }

                    return false;
                }
                else
                    return clear(nextVersion(), false, filter);
            }
        }

        // If version has changed do it again.
        return compact(filter);
    }

    /**
     *
     * @param val New value.
     * @param expireTime Expiration time.
     * @param ttl Time to live.
     * @param ver Update version.
     */
    protected final void update(@Nullable CacheObject val, long expireTime, long ttl, GridCacheVersion ver) {
        assert ver != null;
        assert Thread.holdsLock(this);
        assert ttl != CU.TTL_ZERO && ttl != CU.TTL_NOT_CHANGED && ttl >= 0 : ttl;

        long oldExpireTime = expireTimeExtras();

        if (oldExpireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().removeTrackedEntry(this);

        value(val);

        ttlAndExpireTimeExtras(ttl, expireTime);

        if (expireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().addTrackedEntry(this);

        this.ver = ver;
    }

    /**
     * Update TTL if it is changed.
     *
     * @param expiryPlc Expiry policy.
     */
    private void updateTtl(ExpiryPolicy expiryPlc) {
        long ttl = CU.toTtl(expiryPlc.getExpiryForAccess());

        if (ttl != CU.TTL_NOT_CHANGED)
            updateTtl(ttl);
    }

    /**
     * Update TTL is it is changed.
     *
     * @param expiryPlc Expiry policy.
     * @throws IgniteCheckedException If failed.
     * @throws GridCacheEntryRemovedException If failed.
     */
    private void updateTtl(IgniteCacheExpiryPolicy expiryPlc)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        long ttl = expiryPlc.forAccess();

        if (ttl != CU.TTL_NOT_CHANGED) {
            updateTtl(ttl);

            expiryPlc.ttlUpdated(key(),
                version(),
                hasReaders() ? ((GridDhtCacheEntry)this).readers() : null);
        }
    }

    /**
     * @param ttl Time to live.
     */
    private void updateTtl(long ttl) {
        assert ttl >= 0 || ttl == CU.TTL_ZERO : ttl;
        assert Thread.holdsLock(this);

        long expireTime;

        if (ttl == CU.TTL_ZERO) {
            ttl = CU.TTL_MINIMUM;
            expireTime = CU.expireTimeInPast();
        }
        else
            expireTime = CU.toExpireTime(ttl);

        long oldExpireTime = expireTimeExtras();

        if (oldExpireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().removeTrackedEntry(this);

        ttlAndExpireTimeExtras(ttl, expireTime);

        if (expireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().addTrackedEntry(this);
    }

    /**
     * @return {@code True} if values should be stored off-heap.
     */
    protected boolean isOffHeapValuesOnly() {
        return cctx.config().getMemoryMode() == CacheMemoryMode.OFFHEAP_VALUES;
    }

    /**
     * @throws GridCacheEntryRemovedException If entry is obsolete.
     */
    protected void checkObsolete() throws GridCacheEntryRemovedException {
        assert Thread.holdsLock(this);

        if (obsoleteVersionExtras() != null)
            throw new GridCacheEntryRemovedException();
    }

    /** {@inheritDoc} */
    @Override public KeyCacheObject key() {
        return key;
    }

    /** {@inheritDoc} */
    @Override public IgniteTxKey txKey() {
        return cctx.txKey(key);
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheVersion version() throws GridCacheEntryRemovedException {
        checkObsolete();

        return ver;
    }

    /**
     * Gets hash value for the entry key.
     *
     * @return Hash value.
     */
    int hash() {
        return hash;
    }

    /**
     * Gets next entry in bucket linked list within a hash map segment.
     *
     * @param segId Segment ID.
     * @return Next entry.
     */
    GridCacheMapEntry next(int segId) {
        return segId % 2 == 0 ? next0 : next1;
    }

    /**
     * Sets next entry in bucket linked list within a hash map segment.
     *
     * @param segId Segment ID.
     * @param next Next entry.
     */
    void next(int segId, @Nullable GridCacheMapEntry next) {
        if (segId % 2 == 0)
            next0 = next;
        else
            next1 = next;
    }

    /** {@inheritDoc} */
    @Nullable @Override public <K, V> CacheObject peek(GridCachePeekMode mode,
        CacheEntryPredicate[] filter)
        throws GridCacheEntryRemovedException {
        try {
            GridTuple<CacheObject> peek = peek0(false, mode, filter, cctx.tm().localTxx());

            return peek != null ? peek.get() : null;
        }
        catch (GridCacheFilterFailedException ignore) {
            assert false;

            return null;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Unable to perform entry peek() operation.", e);
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public CacheObject peek(boolean heap,
        boolean offheap,
        boolean swap,
        long topVer,
        @Nullable IgniteCacheExpiryPolicy expiryPlc)
        throws GridCacheEntryRemovedException, IgniteCheckedException
    {
        assert heap || offheap || swap;

        try {
            if (heap) {
                GridTuple<CacheObject> val = peekGlobal(false, topVer, null, expiryPlc);

                if (val != null)
                    return val.get();
            }

            if (offheap || swap) {
                GridCacheSwapEntry  e = cctx.swap().read(this, false, offheap, swap);

                return e != null ? e.value() : null;
            }

            return null;
        }
        catch (GridCacheFilterFailedException ignored) {
            assert false;

            return null;
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> CacheObject peek(Collection<GridCachePeekMode> modes,
        CacheEntryPredicate[] filter)
        throws GridCacheEntryRemovedException {
        assert modes != null;

        for (GridCachePeekMode mode : modes) {
            try {
                GridTuple<CacheObject> val = peek0(false, mode, filter, cctx.tm().localTxx());

                if (val != null)
                    return val.get();
            }
            catch (GridCacheFilterFailedException ignored) {
                assert false;

                return null;
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException("Unable to perform entry peek() operation.", e);
            }
        }

        return null;
    }

    /**
     * @param failFast Fail-fast flag.
     * @param mode Peek mode.
     * @param filter Filter.
     * @param tx Transaction to peek value at (if mode is TX value).
     * @return Peeked value.
     * @throws IgniteCheckedException In case of error.
     * @throws GridCacheEntryRemovedException If removed.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable @Override public GridTuple<CacheObject> peek0(boolean failFast, GridCachePeekMode mode,
        CacheEntryPredicate[] filter, @Nullable IgniteInternalTx tx)
        throws GridCacheEntryRemovedException, GridCacheFilterFailedException, IgniteCheckedException {
        assert tx == null || tx.local();

        long topVer = tx != null ? tx.topologyVersion() : cctx.affinity().affinityTopologyVersion();

        switch (mode) {
            case TX:
                return peekTx(failFast, filter, tx);

            case GLOBAL:
                return peekGlobal(failFast, topVer, filter, null);

            case NEAR_ONLY:
                return peekGlobal(failFast, topVer, filter, null);

            case PARTITIONED_ONLY:
                return peekGlobal(failFast, topVer, filter, null);

            case SMART:
                /*
                 * If there is no ongoing transaction, or transaction is NOT in ACTIVE state,
                 * which means that it is either rolling back, preparing to commit, or committing,
                 * then we only check the global cache storage because value has already been
                 * validated against filter and enlisted into transaction and, therefore, second
                 * validation against the same enlisted value will be invalid (it will always be false).
                 *
                 * However, in ACTIVE state, we must also validate against other values that
                 * may have enlisted into the same transaction and that's why we pass 'true'
                 * to 'e.peek(true)' method in this case.
                 */
                return tx == null || tx.state() != ACTIVE ? peekGlobal(failFast, topVer, filter, null) :
                    peekTxThenGlobal(failFast, filter, tx);

            case SWAP:
                return peekSwap(failFast, filter);

            case DB:
                return F.t(peekDb(failFast, filter));

            default: // Should never be reached.
                assert false;

                return null;
        }
    }

    /** {@inheritDoc} */
    @Override public CacheObject poke(CacheObject val) throws GridCacheEntryRemovedException, IgniteCheckedException {
        assert val != null;

        CacheObject old;

        synchronized (this) {
            checkObsolete();

            if (isNew() || !valid(-1))
                unswap(true, true);

            if (deletedUnlocked())
                return null;

            old = rawGetOrUnmarshalUnlocked(false);

            GridCacheVersion nextVer = nextVersion();

            // Update index inside synchronization since it can be updated
            // in load methods without actually holding entry lock.
            long expireTime = expireTimeExtras();

            val = cctx.kernalContext().portable().prepareForCache(val, cctx);

            updateIndex(val, expireTime, nextVer, old);

            update(val, expireTime, ttlExtras(), nextVer);
        }

        if (log.isDebugEnabled())
            log.debug("Poked cache entry [newVal=" + val + ", oldVal=" + old + ", entry=" + this + ']');

        return old;
    }

    /**
     * Checks that entries in group locks transactions are not locked during commit.
     *
     * @param tx Transaction to check.
     * @throws GridCacheEntryRemovedException If entry is obsolete.
     * @throws IgniteCheckedException If entry was externally locked.
     */
    private void groupLockSanityCheck(IgniteInternalTx tx) throws GridCacheEntryRemovedException, IgniteCheckedException {
        assert tx.groupLock();

        IgniteTxEntry txEntry = tx.entry(txKey());

        if (txEntry.groupLockEntry()) {
            if (lockedByAny())
                throw new IgniteCheckedException("Failed to update cache entry (entry was externally locked while " +
                    "accessing entry within group lock transaction) [entry=" + this + ", tx=" + tx + ']');
        }
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @param tx Transaction to peek value at (if mode is TX value).
     * @return Peeked value.
     * @throws GridCacheFilterFailedException If filter failed.
     * @throws GridCacheEntryRemovedException If entry got removed.
     * @throws IgniteCheckedException If unexpected cache failure occurred.
     */
    @Nullable private <K, V> GridTuple<CacheObject> peekTxThenGlobal(boolean failFast,
        CacheEntryPredicate[] filter,
        IgniteInternalTx tx)
        throws GridCacheFilterFailedException, GridCacheEntryRemovedException, IgniteCheckedException
    {
        GridTuple<CacheObject> peek = peekTx(failFast, filter, tx);

        // If transaction has value (possibly null, which means value is to be deleted).
        if (peek != null)
            return peek;

        long topVer = tx == null ? cctx.affinity().affinityTopologyVersion() : tx.topologyVersion();

        return peekGlobal(failFast, topVer, filter, null);
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @param tx Transaction to peek value at (if mode is TX value).
     * @return Peeked value.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @Nullable private <K, V> GridTuple<CacheObject> peekTx(boolean failFast,
        CacheEntryPredicate[] filter,
        @Nullable IgniteInternalTx tx) throws GridCacheFilterFailedException {
        return tx == null ? null : tx.peek(cctx, failFast, key, filter);
    }

    /**
     * @param failFast Fail fast flag.
     * @param topVer Topology version.
     * @param filter Filter.
     * @param expiryPlc Optional expiry policy.
     * @return Peeked value.
     * @throws GridCacheFilterFailedException If filter failed.
     * @throws GridCacheEntryRemovedException If entry got removed.
     * @throws IgniteCheckedException If unexpected cache failure occurred.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable private <K, V> GridTuple<CacheObject> peekGlobal(boolean failFast,
        long topVer,
        CacheEntryPredicate[] filter,
        @Nullable IgniteCacheExpiryPolicy expiryPlc
        )
        throws GridCacheEntryRemovedException, GridCacheFilterFailedException, IgniteCheckedException {
        if (!valid(topVer))
            return null;

        boolean rmv = false;

        try {
            while (true) {
                GridCacheVersion ver;
                CacheObject val;

                synchronized (this) {
                    if (checkExpired()) {
                        rmv = markObsolete0(cctx.versions().next(this.ver), true);

                        return null;
                    }

                    checkObsolete();

                    ver = this.ver;
                    val = rawGetOrUnmarshalUnlocked(false);

                    if (val != null && expiryPlc != null)
                        updateTtl(expiryPlc);
                }

                if (!cctx.isAll(this, filter))
                    return F.t(CU.<CacheObject>failed(failFast));

                if (F.isEmptyOrNulls(filter) || ver.equals(version()))
                    return F.t(val);
            }
        }
        finally {
            if (rmv) {
                onMarkedObsolete();

                cctx.cache().map().removeEntry(this);
            }
        }
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @return Value from swap storage.
     * @throws IgniteCheckedException In case of any errors.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable private <K, V> GridTuple<CacheObject> peekSwap(boolean failFast,
        CacheEntryPredicate[] filter)
        throws IgniteCheckedException, GridCacheFilterFailedException
    {
        if (!cctx.isAll(this, filter))
            return F.t(CU.failed(failFast));

        synchronized (this) {
            if (checkExpired())
                return null;
        }

        GridCacheSwapEntry e = cctx.swap().read(this, false, true, true);

        return e != null ? F.t(e.value()) : null;
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @return Value from persistent store.
     * @throws IgniteCheckedException In case of any errors.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable private CacheObject peekDb(boolean failFast, CacheEntryPredicate[] filter)
        throws IgniteCheckedException, GridCacheFilterFailedException {
        if (!cctx.isAll(this, filter))
            return CU.failed(failFast);

        synchronized (this) {
            if (checkExpired())
                return null;
        }

        return cctx.toCacheObject(cctx.store().loadFromStore(cctx.tm().localTxx(), key));
    }

    /**
     * TODO: GG-4009: do we need to generate event and invalidate value?
     *
     * @return {@code true} if expired.
     * @throws IgniteCheckedException In case of failure.
     */
    private boolean checkExpired() throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        long expireTime = expireTimeExtras();

        if (expireTime > 0) {
            long delta = expireTime - U.currentTimeMillis();

            if (log.isDebugEnabled())
                log.debug("Checked expiration time for entry [timeLeft=" + delta + ", entry=" + this + ']');

            if (delta <= 0) {
                releaseSwap();

                clearIndex(saveValueForIndexUnlocked());

                return true;
            }
        }

        return false;
    }

    /**
     * @return Value.
     */
    @Override public synchronized CacheObject rawGet() {
        return val;
    }

    /** {@inheritDoc} */
    @Nullable @Override public synchronized CacheObject rawGetOrUnmarshal(boolean tmp) throws IgniteCheckedException {
        return rawGetOrUnmarshalUnlocked(tmp);
    }

    /**
     * @param tmp If {@code true} can return temporary instance.
     * @return Value (unmarshalled if needed).
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public CacheObject rawGetOrUnmarshalUnlocked(boolean tmp) throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        CacheObject val = this.val;

        if (val != null)
            return val;

        if (valPtr != 0)
            return cctx.fromOffheap(valPtr, tmp);

        return null;
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasValue() {
        return hasValueUnlocked();
    }

    /**
     * @return {@code True} if this entry has value.
     */
    protected boolean hasValueUnlocked() {
        assert Thread.holdsLock(this);

        return val != null || valPtr != 0;
    }

    /** {@inheritDoc} */
    @Override public synchronized CacheObject rawPut(CacheObject val, long ttl) {
        CacheObject old = this.val;

        update(val, CU.toExpireTime(ttl), ttl, nextVersion());

        return old;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Override public boolean initialValue(
        CacheObject val,
        GridCacheVersion ver,
        long ttl,
        long expireTime,
        boolean preload,
        long topVer,
        GridDrType drType)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        synchronized (this) {
            checkObsolete();

            if (isNew() || (!preload && deletedUnlocked())) {
                long expTime = expireTime < 0 ? CU.toExpireTime(ttl) : expireTime;

                val = cctx.kernalContext().portable().prepareForCache(val, cctx);

                if (val != null)
                    updateIndex(val, expTime, ver, null);

                // Version does not change for load ops.
                update(val, expTime, ttl, ver);

                boolean skipQryNtf = false;

                if (val == null) {
                    skipQryNtf = true;

                    if (cctx.deferredDelete() && !isInternal()) {
                        assert !deletedUnlocked();

                        deletedUnlocked(true);
                    }
                }
                else if (deletedUnlocked())
                    deletedUnlocked(false);

                drReplicate(drType, val, ver);

                if (!skipQryNtf) {
                    if (cctx.isLocal() || cctx.isReplicated() || cctx.affinity().primary(cctx.localNode(), key, topVer))
                        cctx.continuousQueries().onEntryUpdated(this, key, val, null, preload);

                    cctx.dataStructures().onEntryUpdated(key, false);
                }

                if (cctx.store().isLocalStore()) {
                    if (val != null)
                        cctx.store().putToStore(null, key, val, ver);
                }

                return true;
            }

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean initialValue(KeyCacheObject key, GridCacheSwapEntry unswapped) throws
        IgniteCheckedException,
        GridCacheEntryRemovedException {
        checkObsolete();

        if (isNew()) {
            CacheObject val = unswapped.value();

            val = cctx.kernalContext().portable().prepareForCache(val, cctx);

            // Version does not change for load ops.
            update(val,
                unswapped.expireTime(),
                unswapped.ttl(),
                unswapped.version()
            );

            return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheVersionedEntryEx versionedEntry() throws IgniteCheckedException {
        boolean isNew = isStartVersion();

        CacheObject val = isNew ? unswap(true, true) : rawGetOrUnmarshalUnlocked(false);

        return new GridCachePlainVersionedEntry<>(key.value(cctx.cacheObjectContext(), true),
            CU.value(val, cctx, true),
            ttlExtras(),
            expireTimeExtras(),
            ver.conflictVersion(),
            isNew);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean versionedValue(CacheObject val,
        GridCacheVersion curVer,
        GridCacheVersion newVer)
        throws IgniteCheckedException, GridCacheEntryRemovedException
    {
        checkObsolete();

        if (curVer == null || curVer.equals(ver)) {
            if (val != this.val) {
                if (newVer == null)
                    newVer = nextVersion();

                CacheObject old = rawGetOrUnmarshalUnlocked(false);

                long ttl = ttlExtras();

                long expTime = CU.toExpireTime(ttl);

                // Detach value before index update.
                val = cctx.kernalContext().portable().prepareForCache(val, cctx);

                if (val != null) {
                    updateIndex(val, expTime, newVer, old);

                    if (deletedUnlocked())
                        deletedUnlocked(false);
                }

                // Version does not change for load ops.
                update(val, expTime, ttl, newVer);
            }

            return true;
        }

        return false;
    }

    /**
     * Gets next version for this cache entry.
     *
     * @return Next version.
     */
    private GridCacheVersion nextVersion() {
        // Do not change topology version when generating next version.
        return cctx.versions().next(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasLockCandidate(GridCacheVersion ver) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.hasCandidate(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasLockCandidate(long threadId) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.localCandidate(threadId) != null;
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByAny(GridCacheVersion... exclude)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && !mvcc.isEmpty(exclude);
    }

    /** {@inheritDoc} */
    @Override public boolean lockedByThread() throws GridCacheEntryRemovedException {
        return lockedByThread(Thread.currentThread().getId());
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedLocally(GridCacheVersion lockVer) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwned(lockVer);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByThread(long threadId, GridCacheVersion exclude)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByThread(threadId, false, exclude);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedLocallyByIdOrThread(GridCacheVersion lockVer, long threadId)
        throws GridCacheEntryRemovedException {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByIdOrThread(lockVer, threadId);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByThread(long threadId) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByThread(threadId, true);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedBy(GridCacheVersion ver) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isOwnedBy(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByThreadUnsafe(long threadId) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByThread(threadId, true);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByUnsafe(GridCacheVersion ver) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isOwnedBy(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedLocallyUnsafe(GridCacheVersion lockVer) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwned(lockVer);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasLockCandidateUnsafe(GridCacheVersion ver) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.hasCandidate(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized Collection<GridCacheMvccCandidate> localCandidates(GridCacheVersion... exclude)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? Collections.<GridCacheMvccCandidate>emptyList() : mvcc.localCandidates(exclude);
    }

    /** {@inheritDoc} */
    @Override public Collection<GridCacheMvccCandidate> remoteMvccSnapshot(GridCacheVersion... exclude) {
        return Collections.emptyList();
    }

    /** {@inheritDoc} */
    @Nullable @Override public synchronized GridCacheMvccCandidate candidate(GridCacheVersion ver)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? null : mvcc.candidate(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheMvccCandidate localCandidate(long threadId)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? null : mvcc.localCandidate(threadId);
    }

    /** {@inheritDoc} */
    @Override public GridCacheMvccCandidate candidate(UUID nodeId, long threadId)
        throws GridCacheEntryRemovedException {
        boolean loc = cctx.nodeId().equals(nodeId);

        synchronized (this) {
            checkObsolete();

            GridCacheMvcc mvcc = mvccExtras();

            return mvcc == null ? null : loc ? mvcc.localCandidate(threadId) :
                mvcc.remoteCandidate(nodeId, threadId);
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheMvccCandidate localOwner() throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? null : mvcc.localOwner();
    }

    /** {@inheritDoc} */
    @Override public synchronized long rawExpireTime() {
        return expireTimeExtras();
    }

    /** {@inheritDoc} */
    @Override public long expireTimeUnlocked() {
        assert Thread.holdsLock(this);

        return expireTimeExtras();
    }

    /** {@inheritDoc} */
    @Override public boolean onTtlExpired(GridCacheVersion obsoleteVer) {
        boolean obsolete = false;
        boolean deferred = false;

        try {
            synchronized (this) {
                CacheObject expiredVal = val;

                boolean hasOldBytes = valPtr != 0;

                boolean expired = checkExpired();

                if (expired) {
                    if (cctx.deferredDelete() && !detached() && !isInternal()) {
                        if (!deletedUnlocked()) {
                            update(null, 0L, 0L, ver);

                            deletedUnlocked(true);

                            deferred = true;
                        }
                    }
                    else {
                        if (markObsolete0(obsoleteVer, true))
                            obsolete = true; // Success, will return "true".
                    }

                    if (cctx.events().isRecordable(EVT_CACHE_OBJECT_EXPIRED)) {
                        cctx.events().addEvent(partition(),
                            key,
                            cctx.localNodeId(),
                            null,
                            EVT_CACHE_OBJECT_EXPIRED,
                            null,
                            false,
                            expiredVal,
                            expiredVal != null || hasOldBytes,
                            null,
                            null,
                            null);
                    }

                    cctx.continuousQueries().onEntryExpired(this, key, expiredVal);
                }
            }
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to clean up expired cache entry: " + this, e);
        }
        finally {
            if (obsolete)
                onMarkedObsolete();

            if (deferred)
                cctx.onDeferredDelete(this, obsoleteVer);
        }

        return obsolete;
    }

    /** {@inheritDoc} */
    @Override public synchronized long rawTtl() {
        return ttlExtras();
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"IfMayBeConditional"})
    @Override public long expireTime() throws GridCacheEntryRemovedException {
        IgniteTxLocalAdapter tx = currentTx();

        if (tx != null) {
            long time = tx.entryExpireTime(txKey());

            if (time > 0)
                return time;
        }

        synchronized (this) {
            checkObsolete();

            return expireTimeExtras();
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"IfMayBeConditional"})
    @Override public long ttl() throws GridCacheEntryRemovedException {
        IgniteTxLocalAdapter tx = currentTx();

        if (tx != null) {
            long entryTtl = tx.entryTtl(txKey());

            if (entryTtl > 0)
                return entryTtl;
        }

        synchronized (this) {
            checkObsolete();

            return ttlExtras();
        }
    }

    /**
     * @return Current transaction.
     */
    private IgniteTxLocalAdapter currentTx() {
        if (cctx.isDht())
            return cctx.dht().near().context().tm().localTx();
        else
            return cctx.tm().localTx();
    }

    /** {@inheritDoc} */
    @Override public void updateTtl(@Nullable GridCacheVersion ver, long ttl) {
        synchronized (this) {
            updateTtl(ttl);

            /*
            TODO IGNITE-305.
            try {
                if (var == null || ver.equals(version()))
                    updateTtl(ttl);
            }
            catch (GridCacheEntryRemovedException ignored) {
                // No-op.
            }
            */
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized CacheObject valueBytes() throws GridCacheEntryRemovedException {
        checkObsolete();

        return valueBytesUnlocked();
    }

    /** {@inheritDoc} */
    @Nullable @Override public CacheObject valueBytes(@Nullable GridCacheVersion ver)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        CacheObject val = null;

        synchronized (this) {
            checkObsolete();

            if (ver == null || this.ver.equals(ver))
                val = valueBytesUnlocked();
        }

        return val;
    }

    /**
     * Updates cache index.
     *
     * @param val Value.
     * @param expireTime Expire time.
     * @param ver New entry version.
     * @param prevVal Previous value.
     * @throws IgniteCheckedException If update failed.
     */
    protected void updateIndex(@Nullable CacheObject val,
        long expireTime,
        GridCacheVersion ver,
        @Nullable CacheObject prevVal) throws IgniteCheckedException {
        assert Thread.holdsLock(this);
        assert val != null : "null values in update index for key: " + key;

        try {
            GridCacheQueryManager qryMgr = cctx.queries();

            if (qryMgr != null) {
                qryMgr.store(key.value(cctx.cacheObjectContext(), false),
                    null,
                    CU.value(val, cctx, false),
                    null,
                    ver,
                    expireTime);
            }
        }
        catch (IgniteCheckedException e) {
            throw new GridCacheIndexUpdateException(e);
        }
    }

    /**
     * Clears index.
     *
     * @param prevVal Previous value (if needed for index update).
     * @throws IgniteCheckedException If failed.
     */
    protected void clearIndex(@Nullable CacheObject prevVal) throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        try {
            GridCacheQueryManager<?, ?> qryMgr = cctx.queries();

            if (qryMgr != null)
                qryMgr.remove(key().value(cctx.cacheObjectContext(), false));
        }
        catch (IgniteCheckedException e) {
            throw new GridCacheIndexUpdateException(e);
        }
    }

    /**
     * This method will return current value only if clearIndex(V) will require previous value (this is the case
     * for Mongo caches). If previous value is not required, this method will return {@code null}.
     *
     * @return Previous value or {@code null}.
     * @throws IgniteCheckedException If failed to retrieve previous value.
     */
    protected CacheObject saveValueForIndexUnlocked() throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        if (!cctx.cache().isMongoDataCache() && !cctx.cache().isMongoMetaCache())
            return null;

        return rawGetOrUnmarshalUnlocked(false);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <K, V> Cache.Entry<K, V> wrap() {
        try {
            IgniteInternalTx tx = cctx.tm().userTx();

            CacheObject val;

            if (tx != null) {
                GridTuple<CacheObject> peek = tx.peek(cctx, false, key, null);

                val = peek == null ? rawGetOrUnmarshal(false) : peek.get();
            }
            else
                val = rawGetOrUnmarshal(false);

            return new CacheEntryImpl<>(key.<K>value(cctx.cacheObjectContext(), false),
                CU.<V>value(val, cctx, false));
        }
        catch (GridCacheFilterFailedException ignored) {
            throw new IgniteException("Should never happen.");
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to wrap entry: " + this, e);
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> Cache.Entry<K, V> wrapLazyValue() {
        return new LazyValueEntry(key);
    }

    /** {@inheritDoc} */
    @Nullable public CacheObject peekVisibleValue() {
        try {
            IgniteInternalTx tx = cctx.tm().userTx();

            if (tx != null) {
                GridTuple<CacheObject> peek = tx.peek(cctx, false, key, null);

                if (peek != null)
                    return peek.get();
            }

            if (detached())
                return rawGet();

            for (;;) {
                GridCacheEntryEx e = cctx.cache().peekEx(key);

                if (e == null)
                    return null;

                try {
                    return e.peek(GridCachePeekMode.GLOBAL, CU.empty0());
                }
                catch (GridCacheEntryRemovedException ignored) {
                    // No-op.
                }
            }
        }
        catch (GridCacheFilterFailedException ignored) {
            throw new IgniteException("Should never happen.");
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> EvictableEntry<K, V> wrapEviction() {
        return new EvictableEntryImpl<>(this);
    }

    /** {@inheritDoc} */
    @Override public synchronized <K, V> CacheVersionedEntryImpl<K, V> wrapVersioned() {
        return new CacheVersionedEntryImpl<>(key.<K>value(cctx.cacheObjectContext(), false), null, ver);
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean evictInternal(boolean swap, GridCacheVersion obsoleteVer,
        @Nullable CacheEntryPredicate[] filter) throws IgniteCheckedException {
        boolean marked = false;

        try {
            if (F.isEmptyOrNulls(filter)) {
                synchronized (this) {
                    CacheObject prev = saveValueForIndexUnlocked();

                    if (!hasReaders() && markObsolete0(obsoleteVer, false)) {
                        if (swap) {
                            if (!isStartVersion()) {
                                try {
                                    // Write to swap.
                                    swap();
                                }
                                catch (IgniteCheckedException e) {
                                    U.error(log, "Failed to write entry to swap storage: " + this, e);
                                }
                            }
                        }
                        else
                            clearIndex(prev);

                        // Nullify value after swap.
                        value(null);

                        marked = true;

                        return true;
                    }
                }
            }
            else {
                // For optimistic check.
                while (true) {
                    GridCacheVersion v;

                    synchronized (this) {
                        v = ver;
                    }

                    if (!cctx.isAll(/*version needed for sync evicts*/this, filter))
                        return false;

                    synchronized (this) {
                        if (!v.equals(ver))
                            // Version has changed since entry passed the filter. Do it again.
                            continue;

                        CacheObject prevVal = saveValueForIndexUnlocked();

                        if (!hasReaders() && markObsolete0(obsoleteVer, false)) {
                            if (swap) {
                                if (!isStartVersion()) {
                                    try {
                                        // Write to swap.
                                        swap();
                                    }
                                    catch (IgniteCheckedException e) {
                                        U.error(log, "Failed to write entry to swap storage: " + this, e);
                                    }
                                }
                            }
                            else
                                clearIndex(prevVal);

                            // Nullify value after swap.
                            value(null);

                            marked = true;

                            return true;
                        }
                        else
                            return false;
                    }
                }
            }
        }
        catch (GridCacheEntryRemovedException ignore) {
            if (log.isDebugEnabled())
                log.debug("Got removed entry when evicting (will simply return): " + this);

            return true;
        }
        finally {
            if (marked)
                onMarkedObsolete();
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public GridCacheBatchSwapEntry evictInBatchInternal(GridCacheVersion obsoleteVer)
        throws IgniteCheckedException {
        assert Thread.holdsLock(this);
        assert cctx.isSwapOrOffheapEnabled();

        GridCacheBatchSwapEntry ret = null;

        try {
            if (!hasReaders() && markObsolete0(obsoleteVer, false)) {
                if (!isStartVersion() && hasValueUnlocked()) {
                    IgniteUuid valClsLdrId = null;

                    if (val != null)
                        valClsLdrId = cctx.deploy().getClassLoaderId(
                            U.detectObjectClassLoader(val.value(cctx.cacheObjectContext(), false)));

                    IgniteBiTuple<byte[], Byte> valBytes = valueBytes0();

                    ret = new GridCacheBatchSwapEntry(key(),
                        partition(),
                        ByteBuffer.wrap(valBytes.get1()),
                        valBytes.get2(),
                        ver,
                        ttlExtras(),
                        expireTimeExtras(),
                        cctx.deploy().getClassLoaderId(U.detectObjectClassLoader(key.value(cctx.cacheObjectContext(), false))),
                        valClsLdrId);
                }

                value(null);
            }
        }
        catch (GridCacheEntryRemovedException ignored) {
            if (log.isDebugEnabled())
                log.debug("Got removed entry when evicting (will simply return): " + this);
        }

        return ret;
    }

    /**
     * @param filter Entry filter.
     * @return {@code True} if entry is visitable.
     */
    public <K, V> boolean visitable(CacheEntryPredicate[] filter) {
        try {
            if (obsoleteOrDeleted() || (filter != CU.empty0() &&
                !cctx.isAll(this, filter)))
                return false;
        }
        catch (IgniteCheckedException e) {
            U.error(log, "An exception was thrown while filter checking.", e);

            RuntimeException ex = e.getCause(RuntimeException.class);

            if (ex != null)
                throw ex;

            Error err = e.getCause(Error.class);

            if (err != null)
                throw err;

            return false;
        }

        IgniteInternalTx tx = cctx.tm().localTxx();

        return tx == null || !tx.removed(txKey());
    }

    /**
     * Ensures that internal data storage is created.
     *
     * @param size Amount of data to ensure.
     * @return {@code true} if data storage was created.
     */
    private boolean ensureData(int size) {
        if (attributeDataExtras() == null) {
            attributeDataExtras(new GridLeanMap<String, Object>(size));

            return true;
        }
        else
            return false;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 addMeta(String name, V1 val) {
        A.notNull(name, "name", val, "val");

        synchronized (this) {
            ensureData(1);

            return (V1)attributeDataExtras().put(name, val);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 meta(String name) {
        A.notNull(name, "name");

        synchronized (this) {
            GridLeanMap<String, Object> attrData = attributeDataExtras();

            return attrData == null ? null : (V1)attrData.get(name);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 removeMeta(String name) {
        A.notNull(name, "name");

        synchronized (this) {
            GridLeanMap<String, Object> attrData = attributeDataExtras();

            if (attrData == null)
                return null;

            V1 old = (V1)attrData.remove(name);

            if (attrData.isEmpty())
                attributeDataExtras(null);

            return old;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public <V1> boolean removeMeta(String name, V1 val) {
        A.notNull(name, "name", val, "val");

        synchronized (this) {
            GridLeanMap<String, Object> attrData = attributeDataExtras();

            if (attrData == null)
                return false;

            V1 old = (V1)attrData.get(name);

            if (old != null && old.equals(val)) {
                attrData.remove(name);

                if (attrData.isEmpty())
                    attributeDataExtras(null);

                return true;
            }

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean hasMeta(String name) {
        return meta(name) != null;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 putMetaIfAbsent(String name, V1 val) {
        A.notNull(name, "name", val, "val");

        synchronized (this) {
            V1 v = meta(name);

            if (v == null)
                return addMeta(name, val);

            return v;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked", "ClassReferencesSubclass"})
    @Nullable @Override public <V1> V1 putMetaIfAbsent(String name, Callable<V1> c) {
        A.notNull(name, "name", c, "c");

        synchronized (this) {
            V1 v = meta(name);

            if (v == null)
                try {
                    return addMeta(name, c.call());
                }
                catch (Exception e) {
                    throw F.wrap(e);
                }

            return v;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Override public <V1> boolean replaceMeta(String name, V1 curVal, V1 newVal) {
        A.notNull(name, "name", newVal, "newVal", curVal, "curVal");

        synchronized (this) {
            if (hasMeta(name)) {
                V1 val = this.<V1>meta(name);

                if (val != null && val.equals(curVal)) {
                    addMeta(name, newVal);

                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Convenience way for super-classes which implement {@link Externalizable} to
     * serialize metadata. Super-classes must call this method explicitly from
     * within {@link Externalizable#writeExternal(ObjectOutput)} methods implementation.
     *
     * @param out Output to write to.
     * @throws IOException If I/O error occurred.
     */
    @SuppressWarnings({"TooBroadScope"})
    protected void writeExternalMeta(ObjectOutput out) throws IOException {
        Map<String, Object> cp;

        // Avoid code warning (suppressing is bad here, because we need this warning for other places).
        synchronized (this) {
            cp = new GridLeanMap<>(attributeDataExtras());
        }

        out.writeObject(cp);
    }

    /**
     * Convenience way for super-classes which implement {@link Externalizable} to
     * serialize metadata. Super-classes must call this method explicitly from
     * within {@link Externalizable#readExternal(ObjectInput)} methods implementation.
     *
     * @param in Input to read from.
     * @throws IOException If I/O error occurred.
     * @throws ClassNotFoundException If some class could not be found.
     */
    @SuppressWarnings({"unchecked"})
    protected void readExternalMeta(ObjectInput in) throws IOException, ClassNotFoundException {
        GridLeanMap<String, Object> cp = (GridLeanMap<String, Object>)in.readObject();

        synchronized (this) {
            attributeDataExtras(cp);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean deleted() {
        if (!cctx.deferredDelete())
            return false;

        synchronized (this) {
            return deletedUnlocked();
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean obsoleteOrDeleted() {
        return obsoleteVersionExtras() != null ||
            (cctx.deferredDelete() && (deletedUnlocked() || !hasValueUnlocked()));
    }

    /**
     * @return {@code True} if deleted.
     */
    @SuppressWarnings("SimplifiableIfStatement")
    protected boolean deletedUnlocked() {
        assert Thread.holdsLock(this);

        if (!cctx.deferredDelete())
            return false;

        return (flags & IS_DELETED_MASK) != 0;
    }

    /**
     * @param deleted {@code True} if deleted.
     */
    protected void deletedUnlocked(boolean deleted) {
        assert Thread.holdsLock(this);
        assert cctx.deferredDelete();

        if (deleted) {
            assert !deletedUnlocked();

            flags |= IS_DELETED_MASK;

            cctx.decrementPublicSize(this);
        }
        else {
            assert deletedUnlocked();

            flags &= ~IS_DELETED_MASK;

            cctx.incrementPublicSize(this);
        }
    }

    /**
     * @return Attribute data.
     */
    @Nullable private GridLeanMap<String, Object> attributeDataExtras() {
        return extras != null ? extras.attributesData() : null;
    }

    /**
     * @param attrData Attribute data.
     */
    private void attributeDataExtras(@Nullable GridLeanMap<String, Object> attrData) {
        extras = (extras != null) ? extras.attributesData(attrData) : attrData != null ?
            new GridCacheAttributesEntryExtras(attrData) : null;
    }

    /**
     * @return MVCC.
     */
    @Nullable protected GridCacheMvcc mvccExtras() {
        return extras != null ? extras.mvcc() : null;
    }

    /**
     * @param mvcc MVCC.
     */
    protected void mvccExtras(@Nullable GridCacheMvcc mvcc) {
        extras = (extras != null) ? extras.mvcc(mvcc) : mvcc != null ? new GridCacheMvccEntryExtras(mvcc) : null;
    }

    /**
     * @return Obsolete version.
     */
    @Nullable protected GridCacheVersion obsoleteVersionExtras() {
        return extras != null ? extras.obsoleteVersion() : null;
    }

    /**
     * @param obsoleteVer Obsolete version.
     */
    protected void obsoleteVersionExtras(@Nullable GridCacheVersion obsoleteVer) {
        extras = (extras != null) ? extras.obsoleteVersion(obsoleteVer) : obsoleteVer != null ?
            new GridCacheObsoleteEntryExtras(obsoleteVer) : null;
    }

    /**
     * Updates metrics.
     *
     * @param op Operation.
     * @param metrics Update merics flag.
     */
    private void updateMetrics(GridCacheOperation op, boolean metrics) {
        if (metrics && cctx.cache().configuration().isStatisticsEnabled()) {
            if (op == GridCacheOperation.DELETE)
                cctx.cache().metrics0().onRemove();
            else
                cctx.cache().metrics0().onWrite();
        }
    }

    /**
     * @return TTL.
     */
    public long ttlExtras() {
        return extras != null ? extras.ttl() : 0;
    }

    /**
     * @return Expire time.
     */
    public long expireTimeExtras() {
        return extras != null ? extras.expireTime() : 0L;
    }

    /**
     * @param ttl TTL.
     * @param expireTime Expire time.
     */
    protected void ttlAndExpireTimeExtras(long ttl, long expireTime) {
        assert ttl != CU.TTL_NOT_CHANGED && ttl != CU.TTL_ZERO;

        extras = (extras != null) ? extras.ttlAndExpireTime(ttl, expireTime) : ttl != CU.TTL_ETERNAL ?
            new GridCacheTtlEntryExtras(ttl, expireTime) : null;
    }

    /**
     * @return Size of extras object.
     */
    private int extrasSize() {
        return extras != null ? extras.size() : 0;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        // Identity comparison left on purpose.
        return o == this;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return hash;
    }

    /** {@inheritDoc} */
    @Override public synchronized String toString() {
        return S.toString(GridCacheMapEntry.class, this);
    }

    /**
     *
     */
    private class LazyValueEntry<K, V> implements Cache.Entry<K, V> {
        /** */
        private final KeyCacheObject key;

        /**
         * @param key Key.
         */
        private LazyValueEntry(KeyCacheObject key) {
            this.key = key;
        }

        /** {@inheritDoc} */
        @Override public K getKey() {
            return key.value(cctx.cacheObjectContext(), false);
        }

        /** {@inheritDoc} */
        @SuppressWarnings("unchecked")
        @Override public V getValue() {
            return CU.value(peekVisibleValue(), cctx, true);
        }

        /** {@inheritDoc} */
        @SuppressWarnings("unchecked")
        @Override public <T> T unwrap(Class<T> cls) {
            if (cls.isAssignableFrom(IgniteCache.class))
                return (T)cctx.grid().jcache(cctx.name());

            if (cls.isAssignableFrom(getClass()))
                return (T)this;

            if (cls.isAssignableFrom(EvictableEntry.class))
                return (T)wrapEviction();

            if (cls.isAssignableFrom(CacheVersionedEntryImpl.class))
                return (T)wrapVersioned();

            if (cls.isAssignableFrom(GridCacheMapEntry.this.getClass()))
                return (T)GridCacheMapEntry.this;

            throw new IllegalArgumentException("Unwrapping to class is not supported: " + cls);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "IteratorEntry [key=" + key + ']';
        }
    }
}
