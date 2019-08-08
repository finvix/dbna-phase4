/**
 * Copyright (C) 2015-2019 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phase4.servlet.soap;

import java.io.File;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.xml.namespace.QName;

import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.util.AttachmentUtils;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.engine.WSSecurityEngine;
import org.apache.wss4j.dom.engine.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.helger.commons.ValueEnforcer;
import com.helger.commons.collection.impl.CommonsHashSet;
import com.helger.commons.collection.impl.ICommonsList;
import com.helger.commons.collection.impl.ICommonsSet;
import com.helger.commons.debug.GlobalDebug;
import com.helger.commons.error.list.ErrorList;
import com.helger.commons.io.file.FileHelper;
import com.helger.commons.io.stream.HasInputStream;
import com.helger.commons.io.stream.StreamHelper;
import com.helger.commons.state.ESuccess;
import com.helger.commons.string.StringHelper;
import com.helger.phase4.CAS4;
import com.helger.phase4.attachment.WSS4JAttachment;
import com.helger.phase4.attachment.WSS4JAttachmentCallbackHandler;
import com.helger.phase4.crypto.AS4CryptoFactory;
import com.helger.phase4.crypto.ECryptoAlgorithmSign;
import com.helger.phase4.crypto.ECryptoAlgorithmSignDigest;
import com.helger.phase4.ebms3header.Ebms3Messaging;
import com.helger.phase4.ebms3header.Ebms3UserMessage;
import com.helger.phase4.error.EEbmsError;
import com.helger.phase4.model.pmode.IPMode;
import com.helger.phase4.model.pmode.leg.PModeLeg;
import com.helger.phase4.servlet.AS4MessageState;
import com.helger.xml.XMLHelper;

/**
 * This class manages the WSS4J SOAP header
 *
 * @author Philip Helger
 * @author bayerlma
 */
public class SOAPHeaderElementProcessorWSS4J implements ISOAPHeaderElementProcessor
{
  /** The QName for which this processor should be invoked */
  public static final QName QNAME_SECURITY = new QName ("http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
                                                        "Security");
  private static final Logger LOGGER = LoggerFactory.getLogger (SOAPHeaderElementProcessorWSS4J.class);

  private final AS4CryptoFactory m_aCryptoFactory;

  public SOAPHeaderElementProcessorWSS4J (@Nonnull final AS4CryptoFactory aCryptoFactory)
  {
    ValueEnforcer.notNull (aCryptoFactory, "aCryptoFactory");
    m_aCryptoFactory = aCryptoFactory;
  }

  @Nonnull
  public ESuccess processHeaderElement (@Nonnull final Document aSOAPDoc,
                                        @Nonnull final Element aSecurityNode,
                                        @Nonnull final ICommonsList <WSS4JAttachment> aAttachments,
                                        @Nonnull final AS4MessageState aState,
                                        @Nonnull final ErrorList aErrorList)
  {
    final IPMode aPMode = aState.getPMode ();

    // Safety Check
    if (aPMode == null)
      throw new IllegalStateException ("No PMode contained in AS4 state - seems like Ebms3 Messaging header is missing!");

    // Default is Leg 1, gets overwritten when a reference to a message id
    // exists and then uses leg2
    final Locale aLocale = aState.getLocale ();
    final Ebms3Messaging aMessage = aState.getMessaging ();
    PModeLeg aPModeLeg = aPMode.getLeg1 ();
    Ebms3UserMessage aUserMessage = null;
    if (aMessage != null && aMessage.hasUserMessageEntries ())
    {
      aUserMessage = aMessage.getUserMessageAtIndex (0);
      if (aUserMessage != null && StringHelper.hasText (aUserMessage.getMessageInfo ().getRefToMessageId ()))
      {
        aPModeLeg = aPMode.getLeg2 ();
      }
    }

    // Does security - legpart checks if not <code>null</code>
    if (aPModeLeg.getSecurity () != null)
    {
      // Get Signature Algorithm
      Element aSignedNode = XMLHelper.getFirstChildElementOfName (aSecurityNode, CAS4.DS_NS, "Signature");
      if (aSignedNode != null)
      {
        // Go through the security nodes to find the algorithm attribute
        aSignedNode = XMLHelper.getFirstChildElementOfName (aSignedNode, CAS4.DS_NS, "SignedInfo");
        final Element aSignatureAlgorithm = XMLHelper.getFirstChildElementOfName (aSignedNode,
                                                                                  CAS4.DS_NS,
                                                                                  "SignatureMethod");
        String sAlgorithm = aSignatureAlgorithm == null ? null : aSignatureAlgorithm.getAttribute ("Algorithm");
        final ECryptoAlgorithmSign eSignAlgo = ECryptoAlgorithmSign.getFromURIOrNull (sAlgorithm);

        if (eSignAlgo == null)
        {
          LOGGER.error ("Error processing the Security Header, your signing algorithm '" +
                        sAlgorithm +
                        "' is incorrect. Expected one of the following '" +
                        Arrays.asList (ECryptoAlgorithmSign.values ()) +
                        "' algorithms");

          aErrorList.add (EEbmsError.EBMS_FAILED_AUTHENTICATION.getAsError (aLocale));

          return ESuccess.FAILURE;
        }

        if (LOGGER.isDebugEnabled ())
          LOGGER.debug ("Using signature algorithm " + eSignAlgo);

        // Get Signature Digest Algorithm
        aSignedNode = XMLHelper.getFirstChildElementOfName (aSignedNode, CAS4.DS_NS, "Reference");
        aSignedNode = XMLHelper.getFirstChildElementOfName (aSignedNode, CAS4.DS_NS, "DigestMethod");
        sAlgorithm = aSignedNode == null ? null : aSignedNode.getAttribute ("Algorithm");
        final ECryptoAlgorithmSignDigest eSignDigestAlgo = ECryptoAlgorithmSignDigest.getFromURIOrNull (sAlgorithm);

        if (eSignDigestAlgo == null)
        {
          LOGGER.error ("Error processing the Security Header, your signing digest algorithm is incorrect. Expected one of the following'" +
                        Arrays.toString (ECryptoAlgorithmSignDigest.values ()) +
                        "' algorithms");

          aErrorList.add (EEbmsError.EBMS_FAILED_AUTHENTICATION.getAsError (aLocale));

          return ESuccess.FAILURE;
        }
        if (LOGGER.isDebugEnabled ())
          LOGGER.debug ("Using signature digest algorithm " + eSignDigestAlgo);
      }

      // Check attachment validity only if a PartInfo element is available
      if (aUserMessage != null)
      {
        final boolean bBodyPayloadPresent = aState.isSoapBodyPayloadPresent ();

        // Check if Attachment IDs are the same
        for (int i = 0; i < aAttachments.size (); i++)
        {
          String sAttachmentId = aAttachments.get (i).getHeaders ().get (AttachmentUtils.MIME_HEADER_CONTENT_ID);
          sAttachmentId = sAttachmentId.substring ("<attachment=".length (), sAttachmentId.length () - 1);

          // Add +1 because the payload has index 0
          final String sHref = aUserMessage.getPayloadInfo ()
                                           .getPartInfoAtIndex ((bBodyPayloadPresent ? 1 : 0) + i)
                                           .getHref ();
          if (!sHref.contains (sAttachmentId))
          {
            LOGGER.error ("Error processing the Attachments, the attachment '" +
                          sHref +
                          "' is not valid with what is specified in the usermessage ('" +
                          sAttachmentId +
                          "')");

            aErrorList.add (EEbmsError.EBMS_VALUE_INCONSISTENT.getAsError (aLocale));

            return ESuccess.FAILURE;
          }
        }
      }

      // Signing Verification and Decryption
      try
      {
        // Convert to WSS4J attachments
        final KeyStoreCallbackHandler aKeyStoreCallback = new KeyStoreCallbackHandler (m_aCryptoFactory.getCryptoProperties ());
        final WSS4JAttachmentCallbackHandler aAttachmentCallbackHandler = new WSS4JAttachmentCallbackHandler (aAttachments,
                                                                                                              aState.getResourceHelper ());

        // Configure RequestData needed for the check / decrypt process!
        final RequestData aRequestData = new RequestData ();
        aRequestData.setCallbackHandler (aKeyStoreCallback);
        if (aAttachments.isNotEmpty ())
          aRequestData.setAttachmentCallbackHandler (aAttachmentCallbackHandler);
        aRequestData.setSigVerCrypto (m_aCryptoFactory.getCrypto ());
        aRequestData.setDecCrypto (m_aCryptoFactory.getCrypto ());
        aRequestData.setWssConfig (m_aCryptoFactory.createWSSConfig ());

        // Upon success, the SOAP document contains the decrypted content
        // afterwards!
        final WSSecurityEngine aSecurityEngine = new WSSecurityEngine ();
        final WSHandlerResult aHdlRes = aSecurityEngine.processSecurityHeader (aSOAPDoc, aRequestData);
        final List <WSSecurityEngineResult> aResults = aHdlRes.getResults ();

        // Collect all unique used certificates
        final ICommonsSet <X509Certificate> aCertSet = new CommonsHashSet <> ();
        for (final WSSecurityEngineResult aResult : aResults)
        {
          final X509Certificate aCert = (X509Certificate) aResult.get (WSSecurityEngineResult.TAG_X509_CERTIFICATE);
          if (aCert != null)
            aCertSet.add (aCert);

          final Integer aAction = (Integer) aResult.get (WSSecurityEngineResult.TAG_ACTION);
          if (aAction != null)
            switch (aAction.intValue ())
            {
              case WSConstants.SIGN:
                aState.setSoapSignatureChecked (true);
                break;
              case WSConstants.ENCR:
                aState.setSoapDecrypted (true);
                break;
            }
        }

        if (aCertSet.size () > 1)
        {
          if (GlobalDebug.isDebugMode ())
            LOGGER.warn ("Found " + aCertSet.size () + " different certificates in message: " + aCertSet);
          else
            LOGGER.warn ("Found " + aCertSet.size () + " different certificates in message!");
        }

        // Remember in State
        aState.setUsedCertificate (aCertSet.getAtIndex (0));
        aState.setDecryptedSOAPDocument (aSOAPDoc);

        // Decrypting the Attachments
        final ICommonsList <WSS4JAttachment> aResponseAttachments = aAttachmentCallbackHandler.getAllResponseAttachments ();
        for (final WSS4JAttachment aResponseAttachment : aResponseAttachments)
        {
          // Always copy to a temporary file, so that decrypted content can be
          // read more than once. By default the stream can only be read once
          // Not nice, but working :)
          final File aTempFile = aState.getResourceHelper ().createTempFile ();
          StreamHelper.copyInputStreamToOutputStreamAndCloseOS (aResponseAttachment.getSourceStream (),
                                                                FileHelper.getBufferedOutputStream (aTempFile));
          aResponseAttachment.setSourceStreamProvider (HasInputStream.multiple ( () -> FileHelper.getBufferedInputStream (aTempFile)));
        }

        // Remember in State
        aState.setDecryptedAttachments (aResponseAttachments);
      }
      catch (final IOException | WSSecurityException ex)
      {
        // Decryption or Signature check failed
        LOGGER.error ("Error processing the WSSSecurity Header", ex);

        // TODO we need a way to distinct
        // signature and decrypt WSSecurityException provides no such thing
        aErrorList.add (EEbmsError.EBMS_FAILED_DECRYPTION.getAsError (aLocale));

        return ESuccess.FAILURE;
      }
    }

    return ESuccess.SUCCESS;
  }
}