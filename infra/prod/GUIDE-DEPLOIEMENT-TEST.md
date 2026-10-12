# Guide : déployer le backend et tester l'APK (phase de test, sans nom de domaine)

Ce guide met en place, une seule fois :

1. le **déploiement automatique** du backend sur le serveur Contabo (`169.58.23.91`) à chaque push sur `develop` ;
2. l'accès **HTTPS** à l'API sans acheter de nom de domaine ;
3. la génération d'un **APK Android** pour tester l'application sur ton téléphone.

Durée : environ 1 h, dont ~30 min d'attente (tests, builds).

---

## Principe

### Pourquoi sslip.io

`sslip.io` est un service DNS public et gratuit : tout nom contenant une IP pointe vers cette IP.
`api.169-58-23-91.sslip.io` → `169.58.23.91`.

- Pas de domaine à acheter, pas de DNS ni de reverse DNS à configurer.
- Le HTTPS fonctionne : Caddy obtient un vrai certificat Let's Encrypt pour ces noms.
- L'application mobile garde sa règle de sécurité (HTTPS obligatoire vers un serveur public).

### Limite de la phase de test : les emails

Sans domaine, les emails (code de vérification à l'inscription) sont envoyés mais **Gmail/Outlook les rejettent
ou les mettent en spam**. Pendant le test, on active les comptes à la main (étape 8).

### Ce que fait le job de déploiement (`.github/workflows/deploy.yml`)

À chaque `git push` sur `develop` :

1. tous les tests backend et mobile tournent (même CI que pour `main`) ;
2. si tout est vert, l'image Docker du backend est construite ;
3. elle est copiée sur le serveur par SSH (utilisateur `deploy`, clé SSH, jamais de mot de passe) ;
4. `deploy.sh` démarre la nouvelle version et attend qu'elle réponde ;
5. si elle ne répond pas en 5 minutes, **l'ancienne version est remise automatiquement**.

Sur le serveur, tout est dans `/opt/couplefinance` : base PostgreSQL, stockage des tickets (MinIO), antivirus
(ClamAV), relais d'email (Postfix) et backend. Les mots de passe et clés sont générés automatiquement sur le serveur
au premier déploiement (fichier `/opt/couplefinance/.env`) et ne sont jamais dans Git ni dans GitHub.

---

## Étape 1 — PC : créer la clé de déploiement

**Pourquoi :** GitHub se connectera au serveur avec cette clé, jamais avec le mot de passe root.

```powershell
ssh-keygen -t ed25519 -N '""' -C "github-deploy-couplefinance" -f $HOME\.ssh\couplefinance_deploy
Get-Content $HOME\.ssh\couplefinance_deploy.pub
```

Copie la ligne affichée (`ssh-ed25519 AAAA...`), elle sert à l'étape 2.

---

## Étape 2 — Serveur : préparer l'utilisateur et le dossier

```powershell
ssh root@169.58.23.91
```

Sur le serveur :

```sh
# 2.1 Changer le mot de passe root
passwd

# 2.2 Utilisateur "deploy" (utilisé par GitHub), sans mot de passe, autorisé à piloter Docker
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
echo "COLLE_ICI_LA_LIGNE_ssh-ed25519_DE_L_ETAPE_1" > /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys

# 2.3 Dossier de l'application et réglages de test
#     Les lignes vides (mots de passe, clés) sont remplies automatiquement au premier déploiement.
install -d -o deploy -g deploy /opt/couplefinance
cat > /opt/couplefinance/.env <<'EOF'
RECEIPT_PUBLIC_ENDPOINT=https://files.169-58-23-91.sslip.io
MAIL_DOMAIN=vmi3442910.contaboserver.net
MAIL_HOSTNAME=vmi3442910.contaboserver.net
MAIL_FROM=CoupleFinance <no-reply@vmi3442910.contaboserver.net>
DKIM_SELECTOR=couplefinance
BACKEND_PORT=8091
POSTGRES_PASSWORD=
MINIO_ROOT_USER=couplefinance
MINIO_ROOT_PASSWORD=
MINIO_KMS_SECRET_KEY=
CURSOR_KEY=
JWT_SIGNING_JWK=
IMAGE_TAG=
EOF
chown deploy:deploy /opt/couplefinance/.env
chmod 600 /opt/couplefinance/.env

# 2.4 Outils nécessaires au script de déploiement (chaque commande doit afficher une version)
python3 --version && openssl version && docker compose version
exit
```

---

## Étape 3 — PC : vérifier la clé

```powershell
ssh -i $HOME\.ssh\couplefinance_deploy deploy@169.58.23.91 "docker ps --format '{{.Names}}'"
```

✅ La liste des conteneurs s'affiche **sans demande de mot de passe**. Sinon, reprendre l'étape 2.2.

---

## Étape 4 — PC : configurer les secrets GitHub

**Pourquoi :** le job lit l'IP, l'utilisateur et la clé dans des secrets chiffrés (invisibles dans le code et les logs).

| Secret | Contenu |
|---|---|
| `DEPLOY_HOST` | IP du serveur |
| `DEPLOY_USER` | `deploy` |
| `DEPLOY_SSH_KEY` | clé privée de l'étape 1 |
| `DEPLOY_KNOWN_HOSTS` | empreinte du serveur (empêche de déployer sur un faux serveur) |

```powershell
$gh = "C:\Program Files\GitHub CLI\gh.exe"
$repo = "abdelmouheimen/couple_finance"
& $gh api -X PUT "repos/$repo/environments/production" | Out-Null
& $gh secret set DEPLOY_HOST --repo $repo --env production --body "169.58.23.91"
& $gh secret set DEPLOY_USER --repo $repo --env production --body "deploy"
Get-Content $HOME\.ssh\couplefinance_deploy -Raw | & $gh secret set DEPLOY_SSH_KEY --repo $repo --env production
ssh-keyscan -t ed25519 169.58.23.91 2>$null | & $gh secret set DEPLOY_KNOWN_HOSTS --repo $repo --env production
& $gh secret list --repo $repo --env production
```

✅ La dernière commande affiche les 4 secrets.

---

## Étape 5 — Premier déploiement

1. Sur GitHub, **merger la PR #99** dans `main`.
2. Créer la branche `develop` (ce push déclenche le déploiement) :

```powershell
cd C:\Abdel\sources\financecouple
git fetch origin
git push origin origin/main:refs/heads/develop
& "C:\Program Files\GitHub CLI\gh.exe" run watch --repo abdelmouheimen/couple_finance
```

Durée : ~15 min (tests puis déploiement).
En cas d'échec, afficher l'erreur :

```powershell
& "C:\Program Files\GitHub CLI\gh.exe" run view --log-failed --repo abdelmouheimen/couple_finance
```

Vérifier sur le serveur que les 5 services tournent :

```sh
ssh root@169.58.23.91 "cd /opt/couplefinance && docker compose --env-file .env ps"
```

---

## Étape 6 — Serveur : ouvrir l'accès HTTPS

**Pourquoi :** les ports 80/443 sont déjà utilisés par le Caddy du projet `dpop-reader-kit`. On lui ajoute deux sites
CoupleFinance sans toucher aux siens.

```sh
ssh root@169.58.23.91

# 6.1 Permettre à Caddy de joindre les conteneurs CoupleFinance
docker network connect couplefinance-edge dpop-reader-kit-caddy-1

# 6.2 Trouver le Caddyfile sur le serveur : repérer la ligne « XXXX -> /etc/caddy/Caddyfile »
docker inspect dpop-reader-kit-caddy-1 --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}'
```

Remplacer `XXXX` par le chemin trouvé :

```sh
# 6.3 Ajouter les deux sites (sauvegarde d'abord)
CADDYFILE=XXXX
cp "$CADDYFILE" "$CADDYFILE.bak"
cat >> "$CADDYFILE" <<'EOF'

api.169-58-23-91.sslip.io {
	reverse_proxy couplefinance-prod-backend-1:8080
}

files.169-58-23-91.sslip.io {
	reverse_proxy couplefinance-prod-minio-1:9000
}
EOF

# 6.4 Valider puis recharger Caddy (sans coupure de l'autre site)
docker exec dpop-reader-kit-caddy-1 caddy validate --config /etc/caddy/Caddyfile \
  && docker exec dpop-reader-kit-caddy-1 caddy reload --config /etc/caddy/Caddyfile

# 6.5 Tester
curl -s https://api.169-58-23-91.sslip.io/actuator/health
```

✅ Réponse attendue : `{"status":"UP"}`.

- Si `validate` échoue, rien n'est rechargé : restaurer avec `cp "$CADDYFILE.bak" "$CADDYFILE"`.
- Le branchement réseau de l'étape 6.1 est perdu si le conteneur Caddy de `dpop-reader-kit` est **recréé** : il faut
  alors refaire 6.1 (ou ajouter le réseau externe `couplefinance-edge` au service `caddy` de ce projet).

---

## Étape 7 — PC : générer l'APK

**Pourquoi Expo EAS :** l'APK est construit sur les serveurs d'Expo, pas besoin d'Android Studio. Compte gratuit.
Le profil `preview` de `mobile/eas.json` produit un APK qui parle à `https://api.169-58-23-91.sslip.io`.

1. Créer un compte sur https://expo.dev/signup.
2. Installer l'outil et lier le projet :

```powershell
npm install -g eas-cli
cd C:\Abdel\sources\financecouple
git checkout main
git pull
cd mobile
npm ci
eas login
eas init
```

`eas init` affiche probablement « cannot automatically write to dynamic config » (la config est en `.ts`) et donne
un `projectId`. L'ajouter dans `mobile/app.config.ts`, dans l'objet `config` :

```ts
extra: { eas: { projectId: "LE-PROJECT-ID-AFFICHE" } },
```

3. Lancer le build :

```powershell
eas build -p android --profile preview
```

Durée : 10-20 min. À la fin : un **lien + QR code** pour télécharger l'APK.

4. Sur le téléphone Android : ouvrir le lien, télécharger, installer (autoriser « installation d'applications
   inconnues » pour le navigateur quand Android le demande).

---

## Étape 8 — Tester l'application

1. **S'inscrire** dans l'application.
2. Le mail de vérification n'arrivera probablement pas : **activer le compte à la main** (remplacer l'adresse) :

```sh
ssh root@169.58.23.91
cd /opt/couplefinance
docker compose --env-file .env exec postgres psql -U couplefinance -d couplefinance -c \
  "UPDATE identity.user_account SET status='ACTIVE', email_verified_at=now(), updated_at=now(), version=version+1
   WHERE lower(email)=lower('ton.adresse@gmail.com') AND status='PENDING_VERIFICATION';"
```

✅ Résultat attendu : `UPDATE 1`.

3. **Se connecter** et tester. Faire de même pour le/la partenaire, puis l'inviter dans le foyer.
4. L'envoi de tickets est refusé pendant ~5 min après le premier déploiement (l'antivirus télécharge ses signatures).

---

## Au quotidien

| Je veux… | Je fais… |
|---|---|
| déployer une nouvelle version du backend | `git push` sur `develop` (tests + déploiement + rollback automatiques) |
| une nouvelle version de l'appli | `eas build -p android --profile preview`, puis réinstaller l'APK |
| voir les logs du backend | `ssh root@169.58.23.91 "cd /opt/couplefinance && docker compose --env-file .env logs --tail 100 backend"` |
| revenir à la version précédente | dans `/opt/couplefinance/.env`, remettre l'ancien `IMAGE_TAG` (`docker images couplefinance-backend`), puis `docker compose --env-file .env up -d` |

---

## Plus tard : passer à un vrai nom de domaine

Quand le test est validé : acheter un domaine (~10 €/an), puis suivre la section « Email (DNS) » de
`infra/prod/README.md` (enregistrements A, SPF, DKIM, DMARC et reverse DNS Contabo). Il faudra changer :

- dans `/opt/couplefinance/.env` : `RECEIPT_PUBLIC_ENDPOINT`, `MAIL_DOMAIN`, `MAIL_HOSTNAME`, `MAIL_FROM` ;
- dans le Caddyfile : les deux noms `*.169-58-23-91.sslip.io` ;
- dans `mobile/eas.json` : `EXPO_PUBLIC_API_BASE_URL`, puis refaire l'APK.

Les emails seront alors livrés normalement et l'activation manuelle ne sera plus nécessaire.

---

## ⚠️ Sécurité avant de mettre de vraies données

- Des conteneurs inconnus tournent sur le serveur (`repocket`, `traffmonetizer`) ainsi qu'un service
  `linux-boots.service`. S'ils n'ont pas été installés volontairement, le serveur est peut-être compromis : vérifier
  avant d'y stocker de vraies données financières.
- Une fois une clé SSH personnelle installée pour root, désactiver la connexion par mot de passe :

```sh
printf 'PasswordAuthentication no\nPermitRootLogin prohibit-password\n' > /etc/ssh/sshd_config.d/99-hardening.conf
sshd -t && systemctl reload ssh
```

  (garder la session ouverte et tester une nouvelle connexion dans un autre terminal avant de la fermer).
