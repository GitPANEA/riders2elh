# Deploy ambiente dev

## Comando di deploy

```bash
mvn clean package deploy -Pdev
```

Copia `target/riders2eLH.jar` via SCP su `10.10.7.46:/opt/riders2eLH/` (profilo `dev`, attivo di default).
L'host è definito in `remote.deploy.host` nel `pom.xml`: in caso di dubbio quella è la fonte
autorevole, non questo file.

## Migrazione dal vecchio nome (riderpay → riders2eLH)

Da eseguire **una volta sola**, sul server che ha già il servizio `riderpay` installato.
Finché non viene fatta, un `mvn deploy` copia il nuovo jar in `/opt/riders2eLH/` mentre
systemd continua a servire il vecchio da `/opt/riderpay/`: nessun errore visibile, ma le
modifiche non compaiono.

L'ordine conta: il passo 1 va fatto **prima** di rimuovere `riderpay.service`. Cancellando
il file di unit mentre il servizio e ancora attivo, systemd perde il riferimento al processo
e resta un java orfano che occupa la 9443; il nuovo servizio non parte e l'errore
("address already in use") non indica la causa reale. In quel caso `systemctl stop` non
serve piu: va trovato il PID con `ps aux | grep riders` e terminato a mano.

```bash
# 1. ferma e disabilita il vecchio servizio
sudo systemctl stop riderpay
sudo systemctl disable riderpay

# 2. sposta la directory conservando env file e keystore (contengono i segreti,
#    non sono nel repo e non vanno rigenerati)
sudo mv /opt/riderpay /opt/riders2eLH
sudo mv /opt/riders2eLH/riderpay.env /opt/riders2eLH/riders2eLH.env
sudo mv /opt/riders2eLH/riderpay-keystore.p12 /opt/riders2eLH/riders2eLH-keystore.p12
sudo rm -f /opt/riders2eLH/riderpay.jar

# 3. porta la unit sul server. NON viene copiata da mvn deploy: il task <scp>
#    nel pom.xml trasferisce solo ${finalName}.jar. Da eseguire sulla
#    postazione di sviluppo, nella directory del progetto:
#
#      scp -i "C:\Sirfin Documents\ProdKey\riderpay_deploy_key" \
#          deploy/riders2eLH.service f.cavaliere@10.10.7.46:/tmp/
#
#    poi, di nuovo sul server:
sudo cp /tmp/riders2eLH.service /etc/systemd/system/riders2eLH.service
sudo rm -f /etc/systemd/system/riderpay.service
sudo systemctl daemon-reload
sudo systemctl enable riders2eLH

# 4. deploy del nuovo jar dalla postazione di sviluppo, poi:
sudo systemctl start riders2eLH
sudo systemctl status riders2eLH
```

L'alias interno del keystore resta `riderpay` (è inciso nel file alla generazione):
`key-alias` in `application-local.yml` lo rispecchia e non va cambiato senza rigenerare
il keystore. Rinominare il *file* è invece sicuro.

## Setup una tantum sul server (10.10.7.46)

Solo per un'installazione da zero; se stai migrando, usa la sezione precedente.

```bash
sudo mkdir -p /opt/riders2eLH
sudo chown f.cavaliere:frontend /opt/riders2eLH

# segreti, mai versionati, permessi ristretti
sudo tee /opt/riders2eLH/riders2eLH.env > /dev/null <<'EOF'
DB_PASSWORD=<password reale qui>
KEYSTORE_PASSWORD=<password del keystore, scelta al passo HTTPS>
EOF
sudo chmod 600 /opt/riders2eLH/riders2eLH.env
sudo chown f.cavaliere:frontend /opt/riders2eLH/riders2eLH.env

sudo cp riders2eLH.service /etc/systemd/system/riders2eLH.service
sudo systemctl daemon-reload
sudo systemctl enable riders2eLH
```

## Setup HTTPS (una tantum, sul server)

Il TLS è terminato da Tomcat embedded (`server.ssl.*` in `application-local.yml`), non c'è
reverse proxy davanti. Il keystore va generato sul server: la chiave privata non deve
transitare in rete.

`keytool` non è nel `PATH` — va invocato dal JRE usato dalla unit systemd (verificare la
versione installata con `ls -d /usr/lib/jvm/*/bin/keytool`):

```bash
/usr/lib/jvm/jre-21-openjdk-21.0.11.0.10-1.0.1.el8.x86_64/bin/keytool \
  -genkeypair -alias riderpay -keyalg RSA -keysize 2048 -validity 825 \
  -storetype PKCS12 -keystore /opt/riders2eLH/riders2eLH-keystore.p12 \
  -dname "CN=10.10.7.46" -ext "SAN=ip:10.10.7.46"
```

L'`-alias riderpay` è volutamente invariato, per restare allineato a `key-alias` in
`application-local.yml` e al keystore già esistente in dev. Se si genera un keystore
nuovo si può usare `riders2eLH`, aggiornando l'alias nello stesso momento in entrambi i
posti.

`keytool` chiede la password in modo interattivo: è quella da riportare in
`KEYSTORE_PASSWORD` nell'`EnvironmentFile`. Il `SAN` è obbligatorio — i client validano
quello, non il `CN`; se il servizio verrà raggiunto per nome DNS va aggiunto lì
(`-ext "SAN=dns:nome.interno,ip:10.10.7.46"`).

Il keystore deve appartenere all'utenza del servizio ed essere leggibile solo da lei
(l'ordine conta: prima `chown`, poi `chmod`, altrimenti il processo non riesce ad aprirlo):

```bash
sudo chown f.cavaliere:frontend /opt/riders2eLH/riders2eLH-keystore.p12
sudo chmod 600 /opt/riders2eLH/riders2eLH-keystore.p12
```

Il certificato è **self-signed**: i client devono disattivare la verifica (`curl -k`,
Postman → Settings → SSL certificate verification off). Per uscire da dev serve un
certificato emesso dalla CA aziendale.

## Dopo ogni deploy

```bash
sudo systemctl restart riders2eLH
sudo systemctl status riders2eLH
journalctl -u riders2eLH -f
```

Il riavvio rigenera le chiavi RSA di firma dei token (`RsaKeyProvider`, in memoria e senza
persistenza): **tutti i token emessi prima decadono**, va rieseguita la richiesta di token
prima di qualsiasi altra chiamata. In Postman: cartella "00 - Autenticazione".

Se il servizio non risponde dopo il riavvio, la prima causa da escludere è il keystore
(percorso, proprietario o `KEYSTORE_PASSWORD` non combaciante): con `server.ssl` attivo
l'applicazione non parte affatto, e il journal riporta l'errore in modo esplicito.
