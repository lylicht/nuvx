//! Persistent TLS identity and QR/manual fingerprint pinning for LAN Sync.

use base64::Engine;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::{ClientConfig, ServerConfig};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::net::TcpStream;
use std::sync::Arc;

const VAULT_KEY: &str = "lan_sync_tls_identity";

#[derive(Serialize, Deserialize)]
struct StoredIdentity {
    certificate_der: String,
    private_key_der: String,
}

pub struct Identity {
    pub server: Arc<ServerConfig>,
    pub fingerprint: String,
}

pub fn identity(data_dir: &std::path::Path) -> anyhow::Result<Identity> {
    install_provider();
    let (certificate, private_key) = match crate::vault::get(VAULT_KEY, data_dir) {
        Some(raw) => {
            let stored: StoredIdentity = serde_json::from_slice(&raw)?;
            (
                base64::engine::general_purpose::STANDARD.decode(stored.certificate_der)?,
                base64::engine::general_purpose::STANDARD.decode(stored.private_key_der)?,
            )
        }
        None => {
            let generated = rcgen::generate_simple_self_signed(vec!["vyral.local".to_owned()])?;
            let certificate = generated.cert.der().as_ref().to_vec();
            let private_key = generated.signing_key.serialize_der();
            let stored = StoredIdentity {
                certificate_der: base64::engine::general_purpose::STANDARD.encode(&certificate),
                private_key_der: base64::engine::general_purpose::STANDARD.encode(&private_key),
            };
            crate::vault::set(VAULT_KEY, data_dir, &serde_json::to_vec(&stored)?)?;
            (certificate, private_key)
        }
    };
    let fingerprint = fingerprint(&certificate);
    let certificate_der = CertificateDer::from(certificate);
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(private_key));
    let mut server = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![certificate_der], key)?;
    server.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Identity {
        server: Arc::new(server),
        fingerprint,
    })
}

pub fn fingerprint(certificate_der: &[u8]) -> String {
    Sha256::digest(certificate_der)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

pub fn parse_fingerprint(pin: &str) -> anyhow::Result<[u8; 32]> {
    anyhow::ensure!(
        pin.len() == 64 && pin.bytes().all(|b| b.is_ascii_hexdigit()),
        "TLS fingerprint must be 64 hexadecimal characters"
    );
    let mut decoded = [0u8; 32];
    for (index, byte) in decoded.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&pin[index * 2..index * 2 + 2], 16)?;
    }
    Ok(decoded)
}

pub fn pinned_client(pin: &str) -> anyhow::Result<Arc<ClientConfig>> {
    install_provider();
    let verifier = Arc::new(PinnedCertificate {
        fingerprint: parse_fingerprint(pin)?,
        provider: Arc::new(rustls::crypto::ring::default_provider()),
    });
    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    Ok(Arc::new(config))
}

/// Used only for `/lan/hello` discovery: the request verifies the TLS handshake signature, then checks that the
/// observed certificate fingerprint exactly matches the fingerprint advertised in that same response.
pub(super) fn discovery_client() -> anyhow::Result<Arc<ClientConfig>> {
    install_provider();
    let verifier = Arc::new(DiscoveryCertificate {
        provider: Arc::new(rustls::crypto::ring::default_provider()),
    });
    let config = ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    Ok(Arc::new(config))
}

pub fn connect(
    stream: TcpStream,
    client: Arc<ClientConfig>,
) -> anyhow::Result<rustls::StreamOwned<rustls::ClientConnection, TcpStream>> {
    connect_timeout(stream, client, std::time::Duration::from_secs(15))
}

pub(super) fn connect_timeout(
    stream: TcpStream,
    client: Arc<ClientConfig>,
    timeout: std::time::Duration,
) -> anyhow::Result<rustls::StreamOwned<rustls::ClientConnection, TcpStream>> {
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    let name = ServerName::try_from("vyral.local".to_owned())?;
    let connection = rustls::ClientConnection::new(client, name)?;
    Ok(rustls::StreamOwned::new(connection, stream))
}

pub fn server(
    stream: TcpStream,
    server: Arc<ServerConfig>,
) -> anyhow::Result<rustls::StreamOwned<rustls::ServerConnection, TcpStream>> {
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(std::time::Duration::from_secs(15)))?;
    stream.set_write_timeout(Some(std::time::Duration::from_secs(15)))?;
    let connection = rustls::ServerConnection::new(server)?;
    Ok(rustls::StreamOwned::new(connection, stream))
}

fn install_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

#[derive(Debug)]
struct PinnedCertificate {
    fingerprint: [u8; 32],
    provider: Arc<rustls::crypto::CryptoProvider>,
}

#[derive(Debug)]
struct DiscoveryCertificate {
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl rustls::client::danger::ServerCertVerifier for DiscoveryCertificate {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signature: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            certificate,
            signature,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signature: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            certificate,
            signature,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

impl rustls::client::danger::ServerCertVerifier for PinnedCertificate {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        if Sha256::digest(end_entity.as_ref()).as_slice() == self.fingerprint {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::InvalidCertificate(
                rustls::CertificateError::NotValidForName,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signature: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            certificate,
            signature,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        certificate: &CertificateDer<'_>,
        signature: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            certificate,
            signature,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::client::danger::ServerCertVerifier;

    #[test]
    fn pins_accept_only_the_exact_der_sha256_fingerprint() {
        let generated = rcgen::generate_simple_self_signed(vec!["vyral.local".to_owned()]).unwrap();
        let der = generated.cert.der();
        let pin = fingerprint(der.as_ref());
        let verifier = PinnedCertificate {
            fingerprint: parse_fingerprint(&pin).unwrap(),
            provider: Arc::new(rustls::crypto::ring::default_provider()),
        };
        let result = verifier.verify_server_cert(
            der,
            &[],
            &ServerName::try_from("vyral.local".to_owned()).unwrap(),
            &[],
            rustls::pki_types::UnixTime::now(),
        );
        assert!(result.is_ok());
        let wrong = PinnedCertificate {
            fingerprint: [0; 32],
            provider: verifier.provider.clone(),
        };
        assert!(
            wrong
                .verify_server_cert(
                    der,
                    &[],
                    &ServerName::try_from("vyral.local".to_owned()).unwrap(),
                    &[],
                    rustls::pki_types::UnixTime::now(),
                )
                .is_err()
        );
    }
}
