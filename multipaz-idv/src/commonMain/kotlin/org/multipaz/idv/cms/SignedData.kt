package org.multipaz.idv.cms

import kotlinx.io.bytestring.ByteString
import org.multipaz.asn1.ASN1
import org.multipaz.asn1.ASN1Encoding
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.ASN1Null
import org.multipaz.asn1.ASN1Object
import org.multipaz.asn1.ASN1ObjectIdentifier
import org.multipaz.asn1.ASN1OctetString
import org.multipaz.asn1.ASN1Sequence
import org.multipaz.asn1.ASN1Set
import org.multipaz.asn1.ASN1TagClass
import org.multipaz.asn1.ASN1TaggedObject
import org.multipaz.asn1.OID
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcSignature
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.PublicKey
import org.multipaz.crypto.RsaPublicKey
import org.multipaz.crypto.RsaSignature
import org.multipaz.crypto.Signature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.checkSignature
import org.multipaz.crypto.sign

/** Thrown when CMS `SignedData` (used for a passport's `SOD`) is malformed. */
class CmsException(message: String) : Exception(message)

/**
 * The `id-icao-ldsSecurityObject` content type (ICAO 9303-10 Section 4.6.1), i.e. what a
 * passport's `EF.SOD` carries as its CMS `SignedData.encapContentInfo.eContentType`.
 */
const val OID_LDS_SECURITY_OBJECT = "2.23.136.1.1.1"

/**
 * The fields of a CMS `SignedData` (RFC 5652) needed for passive authentication of a passport's
 * `EF.SOD`. This only supports the specific shape ICAO 9303 requires: no CRLs, a single signer
 * using the `issuerAndSerialNumber` form, and always-present signed attributes (both mandated by
 * ICAO 9303-10 Section 4.6).
 *
 * @property eContentType the encapsulated content's type, e.g. [OID_LDS_SECURITY_OBJECT].
 * @property eContent the encapsulated content, e.g. an encoded `LDSSecurityObject`.
 * @property certificates the certificates carried in the `SignedData`, usually just the signer's.
 * @property digestAlgorithmOid the OID of the hash algorithm used for the `messageDigest` attribute.
 * @property messageDigest the signed `messageDigest` attribute's value: the hash of [eContent].
 * @property signatureAlgorithm the algorithm [signature] was produced with.
 * @property signature the raw signature bytes.
 * @property signedAttributesForVerification the signed attributes, re-tagged as a SET (`0x31`),
 *   i.e. exactly what [signature] covers.
 */
data class SignedData(
    val eContentType: String,
    val eContent: ByteArray,
    val certificates: List<X509Cert>,
    val digestAlgorithmOid: String,
    val messageDigest: ByteArray,
    val signatureAlgorithm: Algorithm,
    val signature: ByteArray,
    val signedAttributesForVerification: ByteArray,
) {
    /** Verifies [signature] against [signedAttributesForVerification] using [publicKey]. */
    suspend fun verifySignature(publicKey: PublicKey) {
        val typedSignature: Signature = when (publicKey) {
            is EcPublicKey -> EcSignature.fromDerEncoded(publicKey.curve.bitSize, signature)
            is RsaPublicKey -> RsaSignature(signature)
            else -> throw CmsException("Unsupported public key type for SignedData verification")
        }
        Crypto.checkSignature(publicKey, signedAttributesForVerification, signatureAlgorithm, typedSignature)
    }

    companion object {
        private const val OID_CONTENT_TYPE_ATTR = "1.2.840.113549.1.9.3"
        private const val OID_MESSAGE_DIGEST_ATTR = "1.2.840.113549.1.9.4"

        /** Parses a `ContentInfo` wrapping a CMS `SignedData`, such as the contents of an `EF.SOD`. */
        fun parse(bytes: ByteArray): SignedData {
            val contentInfo = ASN1.decode(bytes) as? ASN1Sequence
                ?: throw CmsException("ContentInfo is not a SEQUENCE")
            val contentTypeOid = (contentInfo.elements[0] as ASN1ObjectIdentifier).oid
            if (contentTypeOid != OID.PKCS7_SIGNED_DATA.oid) {
                throw CmsException("Expected SignedData content type, got $contentTypeOid")
            }
            val explicitContent = contentInfo.elements[1] as ASN1TaggedObject
            val signedData = ASN1.decode(explicitContent.content) as ASN1Sequence

            var index = 1 // skip version
            val digestAlgorithms = signedData.elements[index++] as ASN1Set
            val encapContentInfo = signedData.elements[index++] as ASN1Sequence
            val eContentType = (encapContentInfo.elements[0] as ASN1ObjectIdentifier).oid
            val eContent = ((encapContentInfo.elements[1] as ASN1TaggedObject).let {
                ASN1.decode(it.content) as ASN1OctetString
            }).value

            var certificates = emptyList<X509Cert>()
            if (index < signedData.elements.size &&
                signedData.elements[index] is ASN1TaggedObject &&
                (signedData.elements[index] as ASN1TaggedObject).tag == 0) {
                val certsTagged = signedData.elements[index++] as ASN1TaggedObject
                val certSeqs = ASN1.decodeMultiple(certsTagged.content)
                certificates = certSeqs.map { X509Cert(ByteString(ASN1.encode(it))) }
            }
            // A [1]-tagged crls field would be next; ICAO SODs never include one, so it's not handled.

            val signerInfos = signedData.elements[index] as ASN1Set
            if (signerInfos.elements.size != 1) {
                throw CmsException("Expected exactly one SignerInfo, got ${signerInfos.elements.size}")
            }
            val signerInfo = signerInfos.elements[0] as ASN1Sequence

            var sIndex = 1 // skip version
            sIndex++ // skip sid (IssuerAndSerialNumber or SubjectKeyIdentifier; not needed to verify)
            val digestAlgorithmOid =
                ((signerInfo.elements[sIndex++] as ASN1Sequence).elements[0] as ASN1ObjectIdentifier).oid
            val signedAttrsTagged = signerInfo.elements[sIndex++] as ASN1TaggedObject
            val signedAttrs = ASN1.decodeMultiple(signedAttrsTagged.content)

            var messageDigest: ByteArray? = null
            for (attrObj in signedAttrs) {
                val attr = attrObj as ASN1Sequence
                val attrType = (attr.elements[0] as ASN1ObjectIdentifier).oid
                if (attrType == OID_MESSAGE_DIGEST_ATTR) {
                    val values = attr.elements[1] as ASN1Set
                    messageDigest = (values.elements[0] as ASN1OctetString).value
                }
            }
            if (messageDigest == null) {
                throw CmsException("SignerInfo is missing the messageDigest signed attribute")
            }

            val signatureAlgorithmSeq = signerInfo.elements[sIndex++] as ASN1Sequence
            val signatureAlgorithm = resolveSignatureAlgorithm(signatureAlgorithmSeq)
            val signature = (signerInfo.elements[sIndex] as ASN1OctetString).value

            // The signature covers the signed attributes re-tagged as an ordinary SET OF (0x31),
            // not however they happen to be tagged ([0] IMPLICIT) inside the SignerInfo (RFC 5652
            // Section 5.4).
            val signedAttributesForVerification =
                ASN1.encode(ASN1Set(signedAttrs))

            return SignedData(
                eContentType = eContentType,
                eContent = eContent,
                certificates = certificates,
                digestAlgorithmOid = digestAlgorithmOid,
                messageDigest = messageDigest,
                signatureAlgorithm = signatureAlgorithm,
                signature = signature,
                signedAttributesForVerification = signedAttributesForVerification,
            )
        }

        /**
         * Builds a `ContentInfo` wrapping a CMS `SignedData` in the shape ICAO 9303 requires for a
         * passport's `EF.SOD`: a single signer using the `issuerAndSerialNumber` form, and always
         * signing over `contentType` + `messageDigest` signed attributes.
         *
         * @param eContentType the encapsulated content's type, e.g. [OID_LDS_SECURITY_OBJECT].
         * @param eContent the encapsulated content, e.g. an encoded `LDSSecurityObject`.
         * @param digestAlgorithm the algorithm to hash [eContent] with for the `messageDigest`
         *   signed attribute; must have a non-null [Algorithm.hashAlgorithmName].
         * @param signerCertificate the signer's (DS) certificate, embedded in the `certificates`
         *   field and referenced from `SignerInfo.sid`.
         * @param signingKey the signer's (DS) private key.
         * @param signatureAlgorithm the algorithm to sign the signed attributes with.
         */
        suspend fun build(
            eContentType: String,
            eContent: ByteArray,
            digestAlgorithm: Algorithm,
            signerCertificate: X509Cert,
            signingKey: PrivateKey,
            signatureAlgorithm: Algorithm,
        ): ByteArray {
            val digestAlgorithmOid = hashAlgorithmOid(digestAlgorithm)
            val messageDigest = Crypto.digest(digestAlgorithm, eContent)

            val digestAlgorithmSeq = ASN1Sequence(listOf(ASN1ObjectIdentifier(digestAlgorithmOid), ASN1Null()))
            val encapContentInfo = ASN1Sequence(listOf(
                ASN1ObjectIdentifier(eContentType),
                explicitTag(0, ASN1OctetString(eContent)),
            ))
            val certificates = implicitSet(0, listOf(ASN1.decode(signerCertificate.encoded.toByteArray())!!))

            val issuerAndSerialNumber = ASN1Sequence(listOf(
                encodeName(signerCertificate.issuer),
                signerCertificate.serialNumber,
            ))
            val signedAttrs = listOf(
                ASN1Sequence(listOf(
                    ASN1ObjectIdentifier(OID_CONTENT_TYPE_ATTR),
                    ASN1Set(listOf(ASN1ObjectIdentifier(eContentType))),
                )),
                ASN1Sequence(listOf(
                    ASN1ObjectIdentifier(OID_MESSAGE_DIGEST_ATTR),
                    ASN1Set(listOf(ASN1OctetString(messageDigest))),
                )),
            )
            val signedAttributesForVerification = ASN1.encode(ASN1Set(signedAttrs))
            val signature = Crypto.sign(signingKey, signatureAlgorithm, signedAttributesForVerification)
            val signatureBytes = signature.toDerEncoded()

            val signerInfo = ASN1Sequence(listOf(
                ASN1Integer(1),
                issuerAndSerialNumber,
                digestAlgorithmSeq,
                implicitSet(0, signedAttrs),
                signatureAlgorithmSeq(signatureAlgorithm),
                ASN1OctetString(signatureBytes),
            ))

            val signedData = ASN1Sequence(listOf(
                ASN1Integer(3),
                ASN1Set(listOf(digestAlgorithmSeq)),
                encapContentInfo,
                certificates,
                ASN1Set(listOf(signerInfo)),
            ))

            val contentInfo = ASN1Sequence(listOf(
                ASN1ObjectIdentifier(OID.PKCS7_SIGNED_DATA.oid),
                explicitTag(0, signedData),
            ))
            return ASN1.encode(contentInfo)
        }

        private fun resolveSignatureAlgorithm(seq: ASN1Sequence): Algorithm {
            val oid = (seq.elements[0] as ASN1ObjectIdentifier).oid
            if (oid == OID.SIGNATURE_RSASSA_PSS.oid) {
                return resolvePssAlgorithm(seq.elements[1] as ASN1Sequence)
            }
            // Note this collapses ESP*/ESB* (fully-specified: curve pinned by the algorithm) back
            // to the plain ES* value: the signature AlgorithmIdentifier only ever encodes the hash,
            // never the curve (that comes from the signer's public key), so ES256/ESP256/ESB256 are
            // all indistinguishable here and equally correct for verification purposes.
            return when (oid) {
                OID.SIGNATURE_ECDSA_SHA256.oid -> Algorithm.ES256
                OID.SIGNATURE_ECDSA_SHA384.oid -> Algorithm.ES384
                OID.SIGNATURE_ECDSA_SHA512.oid -> Algorithm.ES512
                OID.SIGNATURE_RS256.oid -> Algorithm.RS256
                OID.SIGNATURE_RS384.oid -> Algorithm.RS384
                OID.SIGNATURE_RS512.oid -> Algorithm.RS512
                else -> throw CmsException("Unsupported SignerInfo signature algorithm OID $oid")
            }
        }

        // Mirrors org.multipaz.crypto.X509Signed's private RSASSA-PSS handling: the algorithm's OID
        // is shared by PS256/PS384/PS512, so the hash has to come from the params' hashAlgorithm.
        private fun resolvePssAlgorithm(params: ASN1Sequence): Algorithm {
            val hashAlgorithmTag = params.elements
                .filterIsInstance<ASN1TaggedObject>()
                .firstOrNull { it.tag == 0 }
                ?: throw CmsException("RSASSA-PSS-params without an explicit hashAlgorithm isn't supported")
            val hashAlgorithmSeq = ASN1.decode(hashAlgorithmTag.content) as ASN1Sequence
            val hashOid = (hashAlgorithmSeq.elements[0] as ASN1ObjectIdentifier).oid
            return when (hashOid) {
                OID.SHA256.oid -> Algorithm.PS256
                OID.SHA384.oid -> Algorithm.PS384
                OID.SHA512.oid -> Algorithm.PS512
                else -> throw CmsException("Unsupported RSASSA-PSS hash algorithm OID $hashOid")
            }
        }

        private fun signatureAlgorithmSeq(algorithm: Algorithm): ASN1Sequence {
            when (algorithm) {
                Algorithm.PS256 -> return rsaSsaPssParamsSeq(OID.SHA256.oid, saltLength = 32)
                Algorithm.PS384 -> return rsaSsaPssParamsSeq(OID.SHA384.oid, saltLength = 48)
                Algorithm.PS512 -> return rsaSsaPssParamsSeq(OID.SHA512.oid, saltLength = 64)
                else -> {}
            }
            val isEc = algorithm in setOf(
                Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256,
                Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB384, Algorithm.ESB320,
                Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512,
            )
            val oid = when (algorithm) {
                Algorithm.ES256, Algorithm.ESP256, Algorithm.ESB256 -> OID.SIGNATURE_ECDSA_SHA256.oid
                Algorithm.ES384, Algorithm.ESP384, Algorithm.ESB384, Algorithm.ESB320 -> OID.SIGNATURE_ECDSA_SHA384.oid
                Algorithm.ES512, Algorithm.ESP512, Algorithm.ESB512 -> OID.SIGNATURE_ECDSA_SHA512.oid
                Algorithm.RS256 -> OID.SIGNATURE_RS256.oid
                Algorithm.RS384 -> OID.SIGNATURE_RS384.oid
                Algorithm.RS512 -> OID.SIGNATURE_RS512.oid
                else -> throw CmsException("Unsupported SignerInfo signature algorithm $algorithm")
            }
            return if (isEc) {
                ASN1Sequence(listOf(ASN1ObjectIdentifier(oid)))
            } else {
                ASN1Sequence(listOf(ASN1ObjectIdentifier(oid), ASN1Null()))
            }
        }

        // RFC 4055 Section 3.1's RSASSA-PSS-params, matching what CryptoJvm's PSSParameterSpec uses
        // when signing PS256/PS384/PS512 (MGF1 with the same hash, salt length = hash output size).
        private fun rsaSsaPssParamsSeq(hashOid: String, saltLength: Int): ASN1Sequence {
            val hashAlgorithmId = ASN1Sequence(listOf(ASN1ObjectIdentifier(hashOid), ASN1Null()))
            val maskGenAlgorithmId = ASN1Sequence(listOf(ASN1ObjectIdentifier(OID.MGF1.oid), hashAlgorithmId))
            val params = ASN1Sequence(listOf(
                explicitTag(0, hashAlgorithmId),
                explicitTag(1, maskGenAlgorithmId),
                explicitTag(2, ASN1Integer(saltLength.toLong())),
            ))
            return ASN1Sequence(listOf(ASN1ObjectIdentifier(OID.SIGNATURE_RSASSA_PSS.oid), params))
        }

        private fun encodeName(name: X500Name): ASN1Sequence {
            val objs = mutableListOf<ASN1Object>()
            for ((oid, value) in name.components) {
                objs.add(ASN1Set(listOf(ASN1Sequence(listOf(ASN1ObjectIdentifier(oid), value)))))
            }
            return ASN1Sequence(objs)
        }

        private fun explicitTag(tag: Int, obj: ASN1Object): ASN1TaggedObject =
            ASN1TaggedObject(ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, tag, ASN1.encode(obj))

        private fun implicitSet(tag: Int, elements: List<ASN1Object>): ASN1TaggedObject =
            ASN1TaggedObject(
                ASN1TagClass.CONTEXT_SPECIFIC, ASN1Encoding.CONSTRUCTED, tag,
                ASN1.encode(ASN1Set(elements)).let { it.copyOfRange(it.indexOfContent(), it.size) }
            )

        // A SET's DER encoding is `0x31 || length || content`; stripping the identifier and length
        // octets off leaves exactly the content bytes an IMPLICIT-tagged re-encoding needs.
        private fun ByteArray.indexOfContent(): Int {
            var offset = 1
            val lengthByte = this[offset].toInt() and 0xff
            offset += if (lengthByte and 0x80 == 0) 1 else 1 + (lengthByte and 0x7f)
            return offset
        }

        private fun hashAlgorithmOid(algorithm: Algorithm): String = when (algorithm) {
            Algorithm.SHA256 -> OID.SHA256.oid
            Algorithm.SHA384 -> OID.SHA384.oid
            Algorithm.SHA512 -> OID.SHA512.oid
            else -> throw CmsException("Unsupported digest algorithm $algorithm")
        }

        /** The reverse of [hashAlgorithmOid], or `null` if [oid] isn't a supported hash algorithm. */
        fun hashAlgorithmFromOid(oid: String): Algorithm? = when (oid) {
            OID.SHA256.oid -> Algorithm.SHA256
            OID.SHA384.oid -> Algorithm.SHA384
            OID.SHA512.oid -> Algorithm.SHA512
            else -> null
        }
    }
}
