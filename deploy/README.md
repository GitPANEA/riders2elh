# Deploy ambiente dev

## Comando di deploy

```bash
mvn clean package deploy -Pdev
```

Copia `target/riderpay.jar` via SCP su `10.10.7.46:/opt/riderpay/` (profilo `dev`, attivo di default).
L'host è definito in `remote.deploy.host` nel `pom.xml`: in caso di dubbio quella è la fonte
autorevole, non questo file.

## Setup una tantum sul server (10.10.7.46)

```bash
sudo mkdir -p /opt/riderpay
sudo chown f.cavaliere:frontend /opt/riderpay

# segreti, mai versionati, permessi ristretti
sudo tee /opt/riderpay/riderpay.env > /dev/null <<'EOF'
DB_PASSWORD=<password reale qui>
KEYSTORE_PASSWORD=<password del keystore, scelta al passo HTTPS>
EOF
sudo chmod 600 /opt/riderpay/riderpay.env
sudo chown f.cavaliere:frontend /opt/riderpay/riderpay.env

sudo cp riderpay.service /etc/systemd/system/riderpay.service
sudo systemctl daemon-reload
sudo systemctl enable riderpay
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
  -storetype PKCS12 -keystore /opt/riderpay/riderpay-keystore.p12 \
  -dname "CN=10.10.7.46" -ext "SAN=ip:10.10.7.46"
```

`keytool` chiede la password in modo interattivo: è quella da riportare in
`KEYSTORE_PASSWORD` nell'`EnvironmentFile`. Il `SAN` è obbligatorio — i client validano
quello, non il `CN`; se il servizio verrà raggiunto per nome DNS va aggiunto lì
(`-ext "SAN=dns:nome.interno,ip:10.10.7.46"`).

Il keystore deve appartenere all'utenza del servizio ed essere leggibile solo da lei
(l'ordine conta: prima `chown`, poi `chmod`, altrimenti il processo non riesce ad aprirlo):

```bash
sudo chown f.cavaliere:frontend /opt/riderpay/riderpay-keystore.p12
sudo chmod 600 /opt/riderpay/riderpay-keystore.p12
```

Il certificato è **self-signed**: i client devono disattivare la verifica (`curl -k`,
Postman → Settings → SSL certificate verification off). Per uscire da dev serve un
certificato emesso dalla CA aziendale.

## Dopo ogni deploy

```bash
sudo systemctl restart riderpay
sudo systemctl status riderpay
journalctl -u riderpay -f
```

Il riavvio rigenera le chiavi RSA di firma dei token (`RsaKeyProvider`, in memoria e senza
persistenza): **tutti i token emessi prima decadono**, va rieseguita la richiesta di token
prima di qualsiasi altra chiamata. In Postman: cartella "00 - Autenticazione".

Se il servizio non risponde dopo il riavvio, la prima causa da escludere è il keystore
(percorso, proprietario o `KEYSTORE_PASSWORD` non combaciante): con `server.ssl` attivo
l'applicazione non parte affatto, e il journal riporta l'errore in modo esplicito.
