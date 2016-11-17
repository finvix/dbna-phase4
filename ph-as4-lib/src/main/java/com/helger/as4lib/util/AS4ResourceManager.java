package com.helger.as4lib.util;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helger.commons.ValueEnforcer;
import com.helger.commons.collection.ext.CommonsArrayList;
import com.helger.commons.collection.ext.ICommonsList;
import com.helger.commons.concurrent.SimpleReadWriteLock;
import com.helger.commons.io.file.FileIOError;
import com.helger.commons.io.file.FileOperationManager;
import com.helger.commons.io.stream.StreamHelper;

public class AS4ResourceManager implements Closeable
{
  private static final Logger s_aLogger = LoggerFactory.getLogger (AS4ResourceManager.class);
  private static final FileOperationManager s_aFOP = new FileOperationManager ();

  private final SimpleReadWriteLock m_aRWLock = new SimpleReadWriteLock ();
  private final ICommonsList <File> m_aTempFiles = new CommonsArrayList<> ();
  private final ICommonsList <Closeable> m_aCloseables = new CommonsArrayList<> ();

  public AS4ResourceManager ()
  {}

  @Nonnull
  public File createTempFile () throws IOException
  {
    // Create
    final File ret = File.createTempFile ("as4", ".tmp");
    // And remember
    m_aRWLock.writeLocked ( () -> m_aTempFiles.add (ret));
    return ret;
  }

  public void addCloseable (@Nonnull final Closeable aCloseable)
  {
    ValueEnforcer.notNull (aCloseable, "Closeable");
    m_aCloseables.add (aCloseable);
  }

  public void close ()
  {
    // Get and delete all temp files
    final ICommonsList <File> aFiles = m_aRWLock.writeLocked ( () -> {
      final ICommonsList <File> ret = m_aTempFiles.getClone ();
      m_aTempFiles.clear ();
      return ret;
    });
    for (final File aFile : aFiles)
    {
      s_aLogger.info ("Deleting temporary file " + aFile.getAbsolutePath ());
      final FileIOError aError = s_aFOP.deleteFileIfExisting (aFile);
      if (aError.isFailure ())
        s_aLogger.warn ("  Failed to delete " + aFile.getAbsolutePath () + ": " + aError.toString ());
    }

    // Close all closeables
    final ICommonsList <Closeable> aCloseables = m_aRWLock.writeLocked ( () -> {
      final ICommonsList <Closeable> ret = m_aCloseables.getClone ();
      m_aCloseables.clear ();
      return ret;
    });
    for (final Closeable aCloseable : aCloseables)
      StreamHelper.close (aCloseable);
  }
}
