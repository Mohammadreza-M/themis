package org.apache.hadoop.hbase.master;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.coprocessor.BaseMasterObserver;
import org.apache.hadoop.hbase.coprocessor.MasterCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.themis.columns.ColumnUtil;
import org.apache.hadoop.hbase.util.Bytes;

public class ThemisMasterObserver extends BaseMasterObserver {
  private static final Log LOG = LogFactory.getLog(ThemisMasterObserver.class);
  public static final String THEMIS_ENABLE_KEY = "THEMIS_ENABLE";
  
  @Override
  public void preCreateTable(ObserverContext<MasterCoprocessorEnvironment> ctx,
      HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
    boolean themisEnable = false;
    for (HColumnDescriptor columnDesc : desc.getColumnFamilies()) {
      String value = columnDesc.getValue(THEMIS_ENABLE_KEY);
      if (value != null && Boolean.parseBoolean(value)) {
        themisEnable = true;
        break;
      }
    }
    
    if (themisEnable) {
      for (HColumnDescriptor columnDesc : desc.getColumnFamilies()) {
        if (Bytes.equals(ColumnUtil.LOCK_FAMILY_NAME, columnDesc.getName())) {
          throw new DoNotRetryIOException("family '" + ColumnUtil.LOCK_FAMILY_NAME_STRING
              + "' is preserved by themis when " + THEMIS_ENABLE_KEY
              + " is true, please change your family name");
        }
        // make sure TTL and MaxVersion is not set by user
        if (columnDesc.getTimeToLive() != HConstants.FOREVER) {
          throw new DoNotRetryIOException("can not set TTL for family '"
              + columnDesc.getNameAsString() + "' when " + THEMIS_ENABLE_KEY + " is true, TTL="
              + columnDesc.getTimeToLive());
        }
        if (columnDesc.getMaxVersions() != HColumnDescriptor.DEFAULT_VERSIONS
            && columnDesc.getMaxVersions() != Integer.MAX_VALUE) {
          throw new DoNotRetryIOException("can not set MaxVersion for family '"
              + columnDesc.getNameAsString() + "' when " + THEMIS_ENABLE_KEY
              + " is true, MaxVersion=" + columnDesc.getMaxVersions());
        }
        columnDesc.setMaxVersions(Integer.MAX_VALUE);
      }
      desc.addFamily(createLockFamily());
      LOG.info("add family '" + ColumnUtil.LOCK_FAMILY_NAME_STRING + "' for table:" + desc.getNameAsString());
    }    
  }
  
  protected static HColumnDescriptor createLockFamily() {
    HColumnDescriptor desc = new HColumnDescriptor(ColumnUtil.LOCK_FAMILY_NAME);
    desc.setInMemory(true);
    desc.setMaxVersions(1);
    desc.setTimeToLive(HConstants.FOREVER);
    // TODO(cuijianwei) : choose the best bloom filter type
    // desc.setBloomFilterType(BloomType.ROWCOL);
    return desc;
  }
}