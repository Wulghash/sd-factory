package net.catenax.sdhub.service;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.document.JsonDocument;
import com.danubetech.verifiablecredentials.CredentialSubject;
import com.danubetech.verifiablecredentials.VerifiableCredential;
import com.danubetech.verifiablecredentials.VerifiablePresentation;
import com.danubetech.verifiablecredentials.jsonld.VerifiableCredentialContexts;
import lombok.RequiredArgsConstructor;
import net.catenax.sdhub.util.KeystoreProperties;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
@RequiredArgsConstructor
public class SDFactory {

    static final URI SD_VOC_URI = URI.create("https://catena-x.net/selfdescription");
    static final URI TRACEABILITY_VOC_URI = URI.create("https://w3id.org/traceability/v1");

    static {
        try (InputStream sdVocIs = SDFactory.class.getClassLoader().getResourceAsStream("verifiablecredentials.jsonld/sd-document-v0.1.jsonld");
            InputStream trVocIs = SDFactory.class.getClassLoader().getResourceAsStream("verifiablecredentials.jsonld/traceability-v1.jsonld")) {
            VerifiableCredentialContexts.CONTEXTS.put(SD_VOC_URI, JsonDocument.of(com.apicatalog.jsonld.http.media.MediaType.JSON_LD, sdVocIs));
            VerifiableCredentialContexts.CONTEXTS.put(TRACEABILITY_VOC_URI, JsonDocument.of(com.apicatalog.jsonld.http.media.MediaType.JSON_LD, trVocIs));
        } catch (JsonLdError | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Value("${app.db.sd.collectionName}")
    private String sdCollectionName;

    private final KeystoreProperties keystoreProperties;
    private final Signer signer;
    private final MongoTemplate mongoTemplate;
    private final VerifierService verifierService;


    public void storeVC(VerifiableCredential verifiableCredential) {
        Document doc = Document.parse(verifiableCredential.toJson());
        mongoTemplate.save(doc, sdCollectionName);
    }

    public void storeVCWithCheck(VerifiableCredential verifiableCredential) throws Exception {
        var issuer = verifiableCredential.getIssuer();
        var query = new Query();
        query = query.addCriteria(Criteria.where("credentialSubject.id").is(issuer.toString()));
        if (mongoTemplate.exists(query, Document.class, sdCollectionName) && verifierService.createVerifier(verifiableCredential).verifier().verify(verifiableCredential)) {
            storeVC(verifiableCredential);
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The Issuer is not trusted");
        }
    }

    public VerifiableCredential createVC(Map<String, Object> claims, URI holderId) throws Exception {
        CredentialSubject credentialSubject = CredentialSubject.builder()
                .id(holderId)
                .claims(claims)
                .build();
        Date issuanceDate = new Date();
        VerifiableCredential verifiableCredential = VerifiableCredential.builder()
                .context(SD_VOC_URI)
                .issuer(URI.create(keystoreProperties.getCatenax().getDid()))
                .issuanceDate(issuanceDate)
                .type("SD-document")
                .credentialSubject(credentialSubject)
                .build();
        return (VerifiableCredential) signer.getSigned(keystoreProperties.getCatenax().getKeyId().iterator().next(), null, verifiableCredential);
    }

    public VerifiablePresentation createVP(List<VerifiableCredential> verifiableCredentialList, String challenge) throws Exception {
        VerifiablePresentation verifiablePresentation = VerifiablePresentation.builder()
                .holder(URI.create(keystoreProperties.getSdhub().getDid()))
                .build();
        verifiableCredentialList.forEach(vc->vc.addToJsonLDObjectAsJsonArray(verifiablePresentation));
        return (VerifiablePresentation) signer.getSigned(keystoreProperties.getSdhub().getKeyId().iterator().next(), challenge, verifiablePresentation);
    }
}
