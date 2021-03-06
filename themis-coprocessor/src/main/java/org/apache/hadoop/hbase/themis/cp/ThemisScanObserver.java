package org.apache.hadoop.hbase.themis.cp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

public class ThemisScanObserver extends BaseRegionObserver {
  public static final String TRANSACTION_START_TS = "_themisTransationStartTs_";
  private static final Log LOG = LogFactory.getLog(ThemisScanObserver.class);
  
  protected static boolean next(HRegion region, final ThemisServerScanner s,
      List<Result> results, int limit) throws IOException {
    List<KeyValue> values = new ArrayList<KeyValue>();
    for (int i = 0; i < limit;) {
      boolean moreRows = s.next(values, SchemaMetrics.METRIC_NEXTSIZE);
      if (!values.isEmpty()) {
        Result result = new Result(values);
        Pair<List<KeyValue>, List<KeyValue>> pResult = ThemisCpUtil.seperateLockAndWriteKvs(result.list());
        List<KeyValue> lockKvs = pResult.getFirst();
        if (lockKvs.size() == 0) {
          List<KeyValue> putKvs = ThemisCpUtil.getPutKvs(pResult.getSecond());
          // should ignore rows which only contain delete columns
          if (putKvs.size() > 0) {
            // TODO : check there must corresponding data columns by commit column
            Get dataGet = ThemisCpUtil.constructDataGetByPutKvs(putKvs, s.getDataColumnFilter());
            Result dataResult = region.get(dataGet, null);
            if (!dataResult.isEmpty()) {
              results.add(dataResult);
              ++i;
            }
          }
        } else {
          LOG.warn("encounter conflict lock in ThemisScan, row=" + result.getRow());
          results.add(new Result(lockKvs));
          ++i;
        }
      }
      if (!moreRows) {
        return false;
      }
      values.clear();
    }
    return true;
  }
  
  // will do themis next logic if passing ThemisScanner
  @Override
  public boolean preScannerNext(final ObserverContext<RegionCoprocessorEnvironment> e,
      final InternalScanner s, final List<Result> results,
      final int limit, final boolean hasMore) throws IOException {
    try {
      if (s instanceof ThemisServerScanner) {
        ThemisServerScanner pScanner = (ThemisServerScanner)s;
        HRegion region = e.getEnvironment().getRegion();
        boolean more = next(region, pScanner, results, limit);
        e.bypass();
        return more;
      }
      return hasMore;
    } catch (Throwable ex) {
      throw new DoNotRetryIOException("themis exception in preScannerNext", ex);
    }
  }

  // will create ThemisScanner when '_themisTransationStartTs_' is set in the attributes of scan;
  // otherwise, follow the origin read path to do hbase scan
  @Override
  public RegionScanner preScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> e,
      final Scan scan, final RegionScanner s) throws IOException {
    try {
      Long themisStartTs = getStartTsFromAttribute(scan);
      if (themisStartTs != null) {
        checkFamily(e.getEnvironment().getRegion(), scan);
        Scan internalScan = ThemisCpUtil.constructLockAndWriteScan(scan, themisStartTs);
        ThemisServerScanner pScanner = new ThemisServerScanner(e.getEnvironment()
            .getRegion().getScanner(internalScan), scan.getFilter());
        e.bypass();
        return pScanner;
      }
      return s;
    } catch (Throwable ex) {
      throw new DoNotRetryIOException("themis exception in preScannerOpen", ex);
    }
  }
  
  protected void checkFamily(final HRegion region, final Scan scan) throws IOException {
    ThemisProtocolImpl.checkFamily(region, scan.getFamilies());
  }
  
  public static Long getStartTsFromAttribute(Scan scan) {
    byte[] startTsBytes = scan.getAttribute(TRANSACTION_START_TS);
    return startTsBytes == null ? null : Bytes.toLong(startTsBytes);
  }
}