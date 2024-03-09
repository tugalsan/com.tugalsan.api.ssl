/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2023 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j.smime.wrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.Nullable;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.SignerInformation;
import org.shredzone.acme4j.smime.exception.AcmeInvalidMessageException;

/**
 * Represents a signed {@link Message}.
 * <p>
 * This class is generated by {@link SignedMailBuilder}, which also takes care for
 * signature verification and validation.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc7508.html">RFC 7508</a>
 * @since 2.16
 */
public class SignedMail implements Mail {
    private static final ASN1ObjectIdentifier SECURE_HEADER_FIELDS_ID
            = PKCSObjectIdentifiers.pkcs_9.branch("16.2.55");
    private static final Set<String> IGNORE_HEADERS
            = Set.of("CONTENT-TYPE", "MIME-VERSION", "RECEIVED");
    private static final Set<String> REQUIRED_HEADERS
            = Set.of("FROM", "TO", "SUBJECT");

    private final List<MailHeader> headers = new ArrayList<>();

    /**
     * This class is to be constructed only by {@link SignedMailBuilder}.
     */
    SignedMail() {
        // package protected constructor
    }

    /**
     * Imports untrusted headers from the envelope message.
     * <p>
     * All previously imported headers are cleaned before that.
     */
    public void importUntrustedHeaders(Enumeration<Header> en) {
        headers.clear();
        while (en.hasMoreElements()) {
            var h = en.nextElement();
            var name = h.getName();
            if (IGNORE_HEADERS.contains(name.toUpperCase(Locale.ENGLISH))) {
                continue;
            }

            headers.add(new MailHeader(name, h.getValue()));
        }
    }

    /**
     * Imports secured headers from the signed, inner message.
     * <p>
     * The import is strict. If a secured header is also present in the envelope message,
     * it must match exactly.
     *
     * @throws AcmeInvalidMessageException
     *         if the secured header was found in the envelope message, but did not match.
     */
    public void importTrustedHeaders(Enumeration<Header> en) throws AcmeInvalidMessageException {
        while (en.hasMoreElements()) {
            var h = en.nextElement();
            var name = h.getName();
            if (IGNORE_HEADERS.contains(name.toUpperCase(Locale.ENGLISH))) {
                continue;
            }

            var value = h.getValue();
            var count = headers.stream()
                    .filter(mh -> mh.nameEquals(name, false) && mh.valueEquals(value, false))
                    .peek(MailHeader::setTrusted)
                    .count();

            if (count == 0) {
                throw new AcmeInvalidMessageException("Secured header '" + name
                        + "' does not match envelope header");
            }
        }
    }

    /**
     * Imports secured headers from the signed, inner message.
     * <p>
     * The import is relaxed. If the secured header is also found in the envelope message
     * header, it will replace the envelope header.
     */
    public void importTrustedHeadersRelaxed(Enumeration<Header> en) {
        while (en.hasMoreElements()) {
            var h = en.nextElement();
            var name = h.getName();
            if (IGNORE_HEADERS.contains(name.toUpperCase(Locale.ENGLISH))) {
                continue;
            }

            headers.removeIf(mh -> mh.nameEquals(name, true) && !mh.trusted);
            headers.add(new MailHeader(name, h.getValue()).setTrusted());
        }
    }

    /**
     * Imports secured headers from the signature.
     * <p>
     * Depending on the signature, the envelope header is either checked, deleted, or
     * modified.
     *
     * @throws AcmeInvalidMessageException
     *         if the signature header conflicts with the envelope header.
     */
    public void importSignatureHeaders(SignerInformation si) throws AcmeInvalidMessageException {
        var attr = si.getSignedAttributes().get(SECURE_HEADER_FIELDS_ID);
        if (attr == null) {
            return;
        }

        var relaxed = false;
        for (var element : (ASN1Set) attr.getAttributeValues()[0]) {
            if (element instanceof ASN1Enumerated) {
                var algorithm = ((ASN1Enumerated) element).intValueExact();
                switch (algorithm) {
                    case 0:
                        relaxed = false;
                        break;
                    case 1:
                        relaxed = true;
                        break;
                    default:
                        throw new AcmeInvalidMessageException("Unknown algorithm: " + algorithm);
                }
            }
        }

        for (var element : (ASN1Set) attr.getAttributeValues()[0]) {
            if (element instanceof ASN1Sequence) {
                for (var sequenceElement : (ASN1Sequence) element) {
                    var headerField = (ASN1Sequence) sequenceElement;
                    var fieldName = ((ASN1String) headerField.getObjectAt(0)).getString();
                    var fieldValue = ((ASN1String) headerField.getObjectAt(1)).getString();
                    var fieldStatus = 0;
                    if (headerField.size() >= 3) {
                        fieldStatus = ((ASN1Integer) headerField.getObjectAt(2)).intValueExact();
                    }
                    switch (fieldStatus) {
                        case 0:
                            checkDuplicatedField(fieldName, fieldValue, relaxed);
                            break;
                        case 1:
                            deleteField(fieldName, fieldValue, relaxed);
                            break;
                        case 2:
                            modifyField(fieldName, fieldValue, relaxed);
                            break;
                        default:
                            throw new AcmeInvalidMessageException("Unknown field status " + fieldStatus);
                    }
                }
            }
        }
    }

    @Override
    public InternetAddress getFrom() throws AcmeInvalidMessageException {
        try {
            return new InternetAddress(fetchTrustedHeader("FROM"));
        } catch (AddressException ex) {
            throw new AcmeInvalidMessageException("Invalid 'FROM' address", ex);
        }
    }

    @Override
    public InternetAddress getTo() throws AcmeInvalidMessageException {
        try {
            return new InternetAddress(fetchTrustedHeader("TO"));
        } catch (AddressException ex) {
            throw new AcmeInvalidMessageException("Invalid 'TO' address", ex);
        }
    }

    @Override
    public String getSubject() throws AcmeInvalidMessageException {
        return fetchTrustedHeader("SUBJECT");
    }

    @Override
    public Optional<String> getMessageId() {
        return headers.stream()
                .filter(mh -> "MESSAGE-ID".equalsIgnoreCase(mh.name))
                .map(mh -> mh.value)
                .map(String::trim)
                .findFirst();
    }

    @Override
    public Collection<InternetAddress> getReplyTo() throws AcmeInvalidMessageException {
        var replyToList = headers.stream()
                .filter(mh -> "REPLY-TO".equalsIgnoreCase(mh.name))
                .map(mh -> mh.value)
                .map(String::trim)
                .collect(Collectors.toList());

        if (replyToList.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            var result = new ArrayList<InternetAddress>(replyToList.size());
            for (var replyTo : replyToList) {
                result.add(new InternetAddress(replyTo));
            }
            return Collections.unmodifiableList(result);
        } catch (AddressException ex) {
            throw new AcmeInvalidMessageException("Invalid 'REPLY-TO' address", ex);
        }
    }

    @Override
    public boolean isAutoSubmitted() {
        return headers.stream()
                .filter(mh -> "AUTO-SUBMITTED".equalsIgnoreCase(mh.name))
                .map(mh -> mh.value)
                .map(String::trim)
                .map(mh -> mh.toLowerCase(Locale.ENGLISH))
                .anyMatch(h -> h.equals("auto-generated") || h.startsWith("auto-generated;"));
    }

    /**
     * Returns a set of missing, but required secured headers. This list is supposed to
     * be empty on valid messages with secured headers. If there is at least one element,
     * the message must be refused.
     */
    public Set<String> getMissingSecuredHeaders() {
        var missing = new TreeSet<>(REQUIRED_HEADERS);
        headers.stream()
                .filter(mh -> mh.trusted)
                .map(mh -> mh.name)
                .map(mh -> mh.toUpperCase(Locale.ENGLISH))
                .forEach(missing::remove);
        return missing;
    }

    /**
     * Processes a "duplicated" header field status. The signature header must be found
     * with the same value in the envelope message header.
     *
     * @param header
     *         Header name
     * @param value
     *         Expected header value
     * @param relaxed
     *         {@code false}: simple, {@code true}: relaxed algorithm
     * @throws AcmeInvalidMessageException
     *         if a header with the same value was not found
     */
    protected void checkDuplicatedField(String header, String value, boolean relaxed) throws AcmeInvalidMessageException {
        var count = headers.stream()
                .filter(mh -> mh.nameEquals(header, relaxed) && mh.valueEquals(value, relaxed))
                .peek(MailHeader::setTrusted)
                .count();
        if (count == 0) {
            throw new AcmeInvalidMessageException("Secured header '" + header
                    + "' was not found in envelope header");
        }
    }

    /**
     * Processes a "deleted" header field status. The signature header must be found
     * with the same value in the envelope message header, and is then removed from the
     * header.
     *
     * @param header
     *         Header name
     * @param value
     *         Expected header value
     * @param relaxed
     *         {@code false}: simple, {@code true}: relaxed algorithm
     * @throws AcmeInvalidMessageException
     *         if a header with the same value was not found
     */
    protected void deleteField(String header, String value, boolean relaxed) throws AcmeInvalidMessageException {
        if (!headers.removeIf(mh -> mh.nameEquals(header, relaxed) && mh.valueEquals(value, relaxed))) {
            throw new AcmeInvalidMessageException("Secured header '" + header
                    + "' was not found in envelope header for deletion");
        }
    }

    /**
     * Processes a "modified" header field status. The signature header must be found in
     * the envelope message header, and is then replaced with the given value.
     *
     * @param header
     *         Header name
     * @param value
     *         New header value
     * @param relaxed
     *         {@code false}: simple, {@code true}: relaxed algorithm
     * @throws AcmeInvalidMessageException
     *         if the header was not found
     */
    protected void modifyField(String header, String value, boolean relaxed) throws AcmeInvalidMessageException {
        if (!headers.removeIf(mh -> mh.nameEquals(header, relaxed))) {
            throw new AcmeInvalidMessageException("Secured header '" + header
                    + "' was not found in envelope header for modification");
        }
        headers.add(new MailHeader(header, value).setTrusted());
    }

    /**
     * Fetches a trusted header. The header must be present exactly once, and must be
     * marked as trusted, i.e. it was either found in the signed inner message, or was
     * set by the signature headers.
     *
     * @param name
     *         Name of the header, case-insensitive
     * @return Header value
     * @throws AcmeInvalidMessageException
     *         if the header was not found, was found more than once, or is not marked as
     *         trusted
     */
    private String fetchTrustedHeader(String name) throws AcmeInvalidMessageException {
        var candidates = headers.stream()
                .filter(mh -> name.equalsIgnoreCase(mh.name))
                .filter(mh -> mh.trusted)
                .map(mh -> mh.value)
                .map(String::trim)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            throw new AcmeInvalidMessageException("Protected '" + name
                    + "' header is required, but missing");
        }

        if (candidates.size() > 1) {
            throw new AcmeInvalidMessageException("Expecting exactly one protected '"
                    + name + "' header, but found " + candidates.size());
        }

        return candidates.get(0);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        for (var mh : headers) {
            sb.append(mh.toString()).append('\n');
        }
        return sb.toString();
    }

    /**
     * A single mail header.
     */
    private static class MailHeader {
        public final String name;
        public final String value;
        public boolean trusted;

        /**
         * Creates a new mail header.
         *
         * @param name Header name
         * @param value Header value
         */
        public MailHeader(String name, String value) {
            this.name = name;
            this.value = value;
        }

        /**
         * Marks this header as trusted.
         *
         * @return itself
         */
        public MailHeader setTrusted() {
            trusted = true;
            return this;
        }

        /**
         * Checks if the header name equals the expected value.
         *
         * @param expected
         *         Expected name
         * @param relaxed
         *         {@code false}: names must match exactly, {@code true}: case-insensitive
         *         match
         * @return {@code true} if equal
         */
        public boolean nameEquals(@Nullable String expected, boolean relaxed) {
            if (!relaxed) {
                return name.equals(expected);
            }

            if (expected == null) {
                return false;
            }

            return name.equalsIgnoreCase(expected);
        }

        /**
         * Checks if the header value equals the expected value.
         *
         * @param expected
         *         Expected value, may be {@code null}
         * @param relaxed
         *         {@code false}: value must match exactly, {@code true}: differences in
         *         whitespaces are ignored
         * @return {@code true} if equal
         */
        public boolean valueEquals(@Nullable String expected, boolean relaxed) {
            if (!relaxed) {
                return value.equals(expected);
            }

            if (expected == null) {
                return false;
            }

            var normalizedValue = value.replaceAll("\\s+", " ").trim();
            var normalizedExpected = expected.replaceAll("\\s+", " ").trim();
            return normalizedValue.equals(normalizedExpected);
        }

        @Override
        public String toString() {
            return (trusted ? "* " : "  ") + name + ": " + value;
        }
    }

}
