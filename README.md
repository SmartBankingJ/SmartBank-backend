# SmartBank-backend

Backend Java 17/Maven : modèles métier, JDBC et API locale d’authentification.

## Structure

```text
src/main/java/ma/bankdirect/
  model/       Clients, utilisateurs, comptes, transactions et enums
  exception/   Exceptions métier et d’accès aux données
  util/        Gestion des montants avec BigDecimal
  config/      Chargement de la configuration
  db/          Fournisseur de connexions JDBC
  TestApp.java Vérifications manuelles historiques en console
src/main/resources/
  app.properties Configuration de démonstration
  sql/schema.sql Schéma MySQL
  sql/seed.sql   Données de démonstration
```

## Compiler

```bash
mvn clean verify
```

Le connecteur MySQL est une dépendance de ce module uniquement. Pour utiliser la base, adapter la configuration puis importer `schema.sql` et `seed.sql` sur une base de développement dédiée. Ces scripts ne sont pas exécutés automatiquement par la compilation ou par le lancement du frontend.

## État actuel

L’API d’authentification écoute uniquement sur `127.0.0.1:8081`. Elle utilise le serveur HTTP du JDK et Gson ; aucun framework Spring n’est requis. `TestApp` reste un ancien programme manuel ; les nouveaux tests automatisés sont dans `src/test/java`.

Le frontend n’embarque pas la configuration SQL. Les données bancaires de démonstration sont conservées ; `seed.sql` ne crée plus de mot de passe en clair. Les anciennes valeurs en clair ne sont jamais acceptées par l’authentification.

## Configuration locale et démarrage

Créer une base de développement dédiée, par exemple `bankdirect`, avec les outils MySQL. Préparer un compte applicatif limité à cette base ; utiliser un compte de migration ayant les droits CREATE/ALTER pour l’initialisation, puis des droits SELECT/INSERT/UPDATE/DELETE pour le serveur. Aucun compte ni mot de passe SQL n’est créé automatiquement.

Dans le terminal qui servira au lancement :

```powershell
$env:SMARTBANK_DB_USER = 'smartbank_app'
$secret = Read-Host 'Mot de passe MySQL' -AsSecureString
$env:SMARTBANK_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
# Facultatif : SMARTBANK_DB_URL si la base n’est pas bankdirect sur localhost:3306.
.\scripts\run-backend.cmd -Action check
```

Ne pas coller les secrets dans le dépôt ou dans le chat. Le lanceur accepte aussi les variables utilisateur Windows persistantes. Il ne modifie pas leurs valeurs globales.

Pour une base existante dont les tables métier sont déjà présentes :

```powershell
.\scripts\run-backend.cmd -Action migrate
```

La migration ajoute les colonnes `locked_until`, `must_change_password`, `password_changed_at` à `users`, puis les tables `auth_sessions`, `password_resets` et `auth_events`. Elle est rejouable et ne supprime ni comptes ni opérations. Les anciens utilisateurs avec mot de passe en clair doivent passer par la récupération ou une réinitialisation administrative.

Pour initialiser EXPLICITEMENT une base de démonstration dédiée :

```powershell
$temporary = Read-Host 'Mot de passe provisoire de karim (12 à 128 caractères)' -AsSecureString
$env:SMARTBANK_DEMO_PASSWORD = [System.Net.NetworkCredential]::new('', $temporary).Password
.\scripts\run-backend.cmd -Action init-demo
Remove-Item Env:SMARTBANK_DEMO_PASSWORD
```

Cette commande importe le schéma et les données de démonstration dans la base configurée, puis crée ou sécurise l’utilisateur de démonstration `karim`. Elle ne remplace pas son mot de passe s’il est déjà haché. Le mot de passe provisoire doit être changé à la première connexion. Ne pas utiliser cette commande sur une base contenant des données réelles.

Lancer ensuite le serveur (terminal à laisser ouvert) :

```powershell
.\scripts\run-backend.cmd
# Simulation locale optionnelle de remise des codes de récupération :
.\scripts\run-backend.cmd -LocalRecovery
```

Avec `-LocalRecovery`, les codes sont écrits dans `%LOCALAPPDATA%\SmartBankingJ\recovery-outbox\user-<id>.txt`, hors des dépôts. Il s’agit d’une boîte locale pour le développeur, sans envoi d’e-mail/SMS. Pour l’envoi réel, configurer SMTP et utiliser `-EmailRecovery` selon la section dédiée ci-dessous. Sans canal configuré, la récupération est indisponible. Ne pas activer la simulation locale dans un déploiement réel.

Le lanceur utilise le JDK et les JAR locaux si `SMARTBANK_AUTH_LIBS` désigne un dossier de dépendances, ou si le dossier local préparé `%LOCALAPPDATA%\SmartBankingJ\tools\auth-libs` existe. Sinon il utilise Maven. Les JAR n’entrent pas dans Git. Avec Maven seul : `mvn compile exec:java`.

## Contrat API

Réponses JSON, erreurs `{ "code": "...", "message": "..." }`, en-tête `Cache-Control: no-store`. Le frontend peut changer d’URL avec `SMARTBANK_API_URL` ; une URL distante doit utiliser HTTPS. Le serveur livré est une API locale de développement, pas un déploiement bancaire de production.

| Méthode | Route | Contenu / résultat |
| --- | --- | --- |
| GET | `/health` | Disponibilité du processus, pas une vérification MySQL |
| POST | `/auth/login` | `username`, `password` ; jeton et identité publique, avec `mustChangePassword` |
| GET | `/auth/me` | Identité du porteur du jeton, sauf première connexion restreinte |
| GET | `/dashboard` | Projection du tableau de bord du client connecté : comptes, trois opérations terminées, demandes en attente, notifications non lues et conseiller |
| POST | `/auth/logout` | Révocation du jeton ; répétable sans erreur |
| POST | `/auth/change-password` | `currentPassword`, `newPassword` ; nouveau jeton et révocation des anciennes sessions |
| POST | `/auth/forgot-password` | `username` ; réponse neutre, y compris pour un utilisateur inconnu |
| POST | `/auth/reset-password` | `code`, `newPassword` ; code consommé et toutes les sessions révoquées |

Pour les routes authentifiées : `Authorization: Bearer <token>`. Les rôles viennent de la base et ne sont jamais choisis dans la demande de connexion. L’interface desktop actuelle est réservée au rôle CUSTOMER ; les interfaces ADMIN/ADVISOR ne sont pas encore développées.

`GET /dashboard` n’accepte aucun identifiant client fourni par le desktop : le client est dérivé de la session bearer validée. Les demandes en attente sont comptées depuis les transactions `PENDING`. Les notifications utilisent la table `notifications`; le conseiller provient de la relation `customers.advisor_id` vers `advisors`. L’action `migrate` crée ces tables sans effacer les données existantes.

## Règles de sécurité et limites

- PBKDF2-HMAC-SHA256 : 600 000 itérations, sel aléatoire de 16 octets, empreinte de 32 octets. Format versionné ; pas de reprise des anciens mots de passe en clair.
- Cinq échecs sur un utilisateur entraînent un verrouillage de 15 minutes. Le message reste générique ; les compteurs persistent en SQL et supportent les demandes concurrentes.
- Jetons aléatoires de 32 octets, conservés en mémoire côté desktop et uniquement sous empreinte SHA-256 en base. Les sessions expirent après 15 minutes sans appel authentifié et au maximum après 8 heures. La session limitée à la première modification expire après 10 minutes.
- La modification et la récupération du mot de passe révoquent les sessions précédentes. Aucun login automatique après récupération.
- Les codes de récupération ont une durée de 15 minutes et un usage unique. Une nouvelle émission remplace la précédente, avec un délai minimal d’une minute. Ils ne sont jamais retournés par l’API.
- Requêtes préparées, transactions SQL, verrouillage des utilisateurs et événements d’audit sans mot de passe ni jeton brut.
- Limitation HTTP locale : 30 demandes de login par minute et adresse ; 10 pour chacune des routes de récupération et de changement de mot de passe. Taille JSON maximale : 8 Kio.
- La fermeture du programme efface la session locale. En cas de coupure réseau, la révocation distante ne peut être garantie ; le jeton serveur reste soumis à ses délais d’expiration. Le bouton Déconnexion demande bien la révocation côté backend.
- Le schéma métier original reste à compléter pour les rôles sans client et les autres fonctionnalités bancaires. Aucun service de virement réel n’est ajouté ici.

## Tests

```powershell
.\scripts\run-backend.cmd -Action test
# Ou : mvn test
```

Les tests JUnit utilisent H2 en mode MySQL, avec une base isolée par scénario. Ils couvrent la sécurité des mots de passe, les sessions, le verrouillage, la récupération, la concurrence et les endpoints HTTP. Ils ne remplacent pas une recette sur le serveur MySQL réel. Les captures et vérifications graphiques personnelles restent hors des dépôts.

Références : [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), [OWASP Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).

## Administration locale MySQL (Windows)

Ces commandes sont destinees a MySQL 8, avec une base deja initialisee. Les migrations et l'initialisation sont executees avec un compte administrateur ; le serveur d'authentification utilise un compte distinct. Aucun mot de passe dans Git.

### Compte d'execution dedie

Dans un terminal interactif, preparer le profil administrateur (saisir le mot de passe MySQL de `root`) :

```powershell
.\scripts\database-admin.cmd -Action configure
.\scripts\database-admin.cmd -Action provision
```

La deuxieme commande demande un **nouveau** mot de passe pour `smartbank_app`. Elle cree uniquement `'smartbank_app'@'localhost'` et refuse de remplacer un compte existant. Si une erreur survient apres sa creation, inspecter ses droits avant toute reprise : les instructions GRANT ne forment pas une transaction atomique.

| Table | Droits du compte d'execution |
| --- | --- |
| customers | SELECT |
| users | SELECT, UPDATE |
| auth_sessions, password_resets | SELECT, INSERT, UPDATE, DELETE |
| auth_events | INSERT |

Pas de droits sur les autres bases, ni de CREATE, DROP, ALTER, GRANT ou CREATE USER. Ce perimetre couvre l'API d'authentification actuelle ; les futurs services bancaires devront definir leurs propres besoins. Le role fonctionnel CUSTOMER/ADMIN d'un utilisateur SmartBanking est distinct du compte technique MySQL.

Configurer ensuite le terminal qui lance le backend :

```powershell
$env:SMARTBANK_DB_USER = 'smartbank_app'
$secret = Read-Host 'Mot de passe MySQL de smartbank_app' -AsSecureString
$env:SMARTBANK_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$secret.Dispose()
$env:SMARTBANK_DB_URL = 'jdbc:mysql://127.0.0.1:3306/bankdirect?serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000'
.\scripts\run-backend.cmd -Action check
.\scripts\run-backend.cmd -LocalRecovery
```

Ne pas executer `migrate` ou `init-demo` avec ce compte limite. Le profil `mysql_config_editor` sert aux outils d'administration, pas a JDBC. Il est stocke dans le profil Windows hors du depot ; son obscurcissement n'est pas un coffre-fort chiffre.

### Inspection, sauvegarde et restauration de test

```powershell
.\scripts\database-admin.cmd -Action inspect
.\scripts\database-admin.cmd -Action backup
```

Les sauvegardes SQL et leur manifeste SHA-256 sont dans `%LOCALAPPDATA%\SmartBankingJ\backups`, avec acces Windows limite a l'utilisateur courant et SYSTEM. Le script refuse un dossier sous SmartBankingJ. Ne pas copier ces fichiers dans Git ou les livrables partages : ils contiennent les donnees et les empreintes de mots de passe. Les fichiers ne sont pas chiffres ; pour une copie externe, utiliser un stockage chiffre et definir une politique de conservation avec l'equipe.

La sauvegarde utilise `--single-transaction` pour les tables InnoDB. Ne pas lancer de migration ni de DDL pendant son execution. Vues, routines, triggers et evenements sont refuses plutot qu'omis silencieusement. Il s'agit d'une sauvegarde logique de la base applicative, pas des comptes MySQL ni d'une restauration a un instant precis.

Utiliser le chemin SQL affiche par la sauvegarde :

```powershell
.\scripts\database-admin.cmd -Action restore -BackupFile 'C:\chemin\bankdirect-....sql' -TargetDatabase bankdirect_restore_recette
```

La cible doit etre une **nouvelle** base commencant par `bankdirect_restore_`. Une cible existante est refusee ; `bankdirect` n'est jamais ecrasee. Le manifeste doit correspondre au fichier. Importer uniquement une sauvegarde locale de confiance : une empreinte garantit l'integrite, pas l'origine du SQL. Apres import, les tables sont controlees et les sessions/codes de recuperation supprimes pour empecher leur reactivation. En cas d'echec, la base de test partielle est conservee pour diagnostic.

Dans Workbench, comparer les comptes, utilisateurs et operations avec la source, puis verifier les relations. Supprimer la base de recette manuellement lorsqu'elle n'est plus necessaire. Le script ne purge aucune sauvegarde automatiquement.

Parametres optionnels : `-Database`, `-MySqlBin`, `-Port`, `-BackupDirectory` et `-LoginPath`. Pour inspecter avec un compte limite, configurer un autre profil avec `-LoginPath smartbank-runtime -LoginUser smartbank_app`, puis utiliser ce profil avec `inspect`.

### Recette automatique sur MySQL

Les memes 18 scenarios peuvent utiliser MySQL au lieu de H2. Dans un terminal de test, avec un compte autorise a creer/supprimer des bases :

```powershell
$env:SMARTBANK_MYSQL_TEST_SERVER = 'jdbc:mysql://127.0.0.1:3306/'
$env:SMARTBANK_MYSQL_TEST_USER = 'root'
$secret = Read-Host 'Mot de passe MySQL administrateur pour les tests' -AsSecureString
$env:SMARTBANK_MYSQL_TEST_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$secret.Dispose()
.\scripts\run-backend.cmd -Action test
Remove-Item Env:SMARTBANK_MYSQL_TEST_SERVER, Env:SMARTBANK_MYSQL_TEST_USER, Env:SMARTBANK_MYSQL_TEST_PASSWORD
```

Une base `smartbank_test_<uuid>` est creee par scenario, puis supprimee. Seul un serveur local sans nom de base dans l'URL est accepte. Aucun SHUTDOWN n'est envoye a MySQL. En cas d'arret brutal, des bases temporaires peuvent rester ; verifier leur nom avant de les nettoyer. Ces tests de service n'evaluent pas a eux seuls les droits du compte `smartbank_app` : valider aussi la connexion, le changement de mot de passe, la recuperation et la deconnexion avec le backend lance sous ce compte.

### Reste a valider avec l'equipe

- Recette visuelle sur la base locale et compte MySQL limite.
- Frequence de sauvegarde, duree de conservation et stockage externe chiffre.
- Procedure metier de creation/desactivation des utilisateurs et interfaces associees.
- Configuration et recette du fournisseur SMTP choisi ; le SMS reste hors du perimetre actuel.

References : [MySQL mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html), [privileges MySQL](https://dev.mysql.com/doc/refman/8.0/en/privileges-provided.html).

## Recuperation par e-mail SMTP

Le backend envoie le code avec Eclipse Angus / Jakarta Mail 2.0.5. Le destinataire provient uniquement de `customers.email`, associe a `users.customer_id`. L'API n'accepte pas d'adresse dans la demande et ne retourne jamais l'adresse au frontend. L'administrateur doit renseigner et confirmer cette adresse par une procedure de confiance ; aucune verification automatique de propriete de l'adresse n'est ajoutee ici.

### Configuration locale

Dans le terminal du backend, conserver les variables MySQL du compte `smartbank_app`, puis renseigner les valeurs du prestataire SMTP :

```powershell
$env:SMARTBANK_SMTP_HOST = 'smtp.votre-fournisseur.example'
$env:SMARTBANK_SMTP_PORT = '587'
$env:SMARTBANK_SMTP_SECURITY = 'starttls'
$env:SMARTBANK_SMTP_USER = 'identifiant-fourni-par-le-prestataire'
$env:SMARTBANK_SMTP_FROM = 'expediteur-autorise@votre-domaine.example'
$secret = Read-Host 'Mot de passe SMTP ou mot de passe application' -AsSecureString
$env:SMARTBANK_SMTP_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$secret.Dispose()
.\scripts\run-backend.cmd -EmailRecovery
```

Remplacer les exemples par les valeurs reelles. Le prestataire peut imposer un mot de passe d'application, un expediteur valide et une configuration DNS. Ne jamais mettre ces secrets dans Git, dans le chat ou dans `app.properties`. Redemarrer le backend apres modification et arreter l'ancien serveur sur 8081.

Modes : `starttls` (habituellement 587, TLS obligatoire avant authentification) ou `tls` (TLS des la connexion, habituellement 465). Les certificats et le nom du serveur sont verifies. Les serveurs exigeant uniquement OAuth2 necessitent une integration supplementaire ou un relais compatible utilisateur/mot de passe.

`-EmailRecovery` force le mode SMTP meme si une ancienne outbox est configuree. `-LocalRecovery` force le mode fichier ; les deux options sont incompatibles. Sans option, `SMARTBANK_RECOVERY_MODE` accepte `smtp`, `local`, `disabled` ; en son absence l'ancienne configuration d'outbox reste compatible. Aucun repli automatique du SMTP vers un fichier.

Hors Maven, ajouter a `auth-libs` (ou `-Libraries`) : `jakarta.mail-2.0.5.jar`, `jakarta.activation-api-2.1.3.jar`, `angus-activation-2.0.3.jar`. Maven telecharge les dependances du POM. Les JAR restent hors Git.

### Adresse du compte de demonstration

L'adresse du seed est fictive. Dans MySQL avec un compte administrateur :

```sql
SELECT u.username, c.id, c.email
FROM users u JOIN customers c ON c.id = u.customer_id
WHERE u.username = 'karim';
```

Renseigner une adresse reelle **que vous controlez**, en remplacant l'exemple :

```sql
UPDATE customers c JOIN users u ON u.customer_id = c.id
SET c.email = 'votre-adresse-de-test@example.com'
WHERE u.username = 'karim';
```

Ne pas modifier le seed partage avec une adresse personnelle. `smartbank_app` ne peut pas modifier `customers` ; cette operation reste administrative.

### Recette et limites

1. Lancer le backend avec `-EmailRecovery` et le frontend a jour.
2. Demander la recuperation du compte de test. L'interface indique le mode e-mail ; consulter aussi les spams.
3. Copier le code dans l'application, choisir et confirmer un nouveau mot de passe.
4. Verifier le refus de l'ancien mot de passe et du code deja utilise, puis la connexion avec le nouveau mot de passe.
5. Une nouvelle demande apres au moins une minute remplace le code. Sa duree reste de 15 minutes.

HTTP 200 conserve un message neutre pour un compte inconnu, inactif ou sans adresse valide. Le champ `delivery` vaut `email` ou `local` selon la configuration globale, sans reveler l'existence d'un compte. Une erreur SMTP renvoie une indisponibilite generique et annule la transaction : le precedent code reste valable si son remplacement echoue. L'acceptation par le relais SMTP ne garantit pas l'arrivee en boite de reception.

L'envoi est synchrone avec delais SMTP bornes et conserve le verrou utilisateur pendant l'envoi. Pour un volume important ou un deploiement distribue, prevoir une file transactionnelle et une supervision. Une panne entre acceptation SMTP et commit SQL peut produire un code inutilisable : refaire une demande. Ne pas journaliser codes, corps de message ou erreurs brutes du prestataire.

Les tests n'envoient aucun e-mail externe : serveur SMTP factice local, MIME et destinataire, refus de repli STARTTLS en clair, injections d'en-tete, erreurs SMTP et conservation du precedent code. Le mode `local_test` est exclusivement reserve aux serveurs SMTP de test sur loopback, sans identifiants ; il est refuse a distance.

Diagnostic dans le terminal backend apres un echec : `Recovery delivery failed: SMTP_AUTHENTICATION` (identifiant/mot de passe d'application refuse), `SMTP_TLS` (negociation/certificat), `SMTP_CONNECTION` (reseau/delai), `SMTP_REJECTED` (message ou destinataire refuse), `SMTP_OTHER` ou `LOCAL_IO`. Seule cette categorie est journalisee, sans erreur brute du fournisseur, adresse, mot de passe ou code. Le message public reste generique. Si aucun echec ni nouvel evenement n'apparait, verifier l'identifiant exact (tout changement de nom de connexion en base doit etre repercute dans le formulaire).

### Exemple Gmail et validation du 27 novembre 2026

Pour Gmail : `SMARTBANK_SMTP_HOST=smtp.gmail.com`, `SMARTBANK_SMTP_PORT=587`, `SMARTBANK_SMTP_SECURITY=starttls`. `SMARTBANK_SMTP_USER` est l'adresse Gmail complete et `SMARTBANK_SMTP_FROM` doit correspondre a cet expediteur autorise.

Activer la validation en deux etapes du compte Google, puis generer un **mot de passe d'application** pour SmartBanking. Le saisir dans `Read-Host` lorsque le terminal le demande, jamais dans le texte de l'invite ni dans une commande partagee. Utiliser ce mot de passe pour le meme compte que `SMARTBANK_SMTP_USER`, pas le mot de passe Gmail habituel. Certains comptes Google n'autorisent pas les mots de passe d'application.

```powershell
$env:SMARTBANK_SMTP_HOST = 'smtp.gmail.com'
$env:SMARTBANK_SMTP_PORT = '587'
$env:SMARTBANK_SMTP_SECURITY = 'starttls'
$env:SMARTBANK_SMTP_USER = (Read-Host 'Adresse Gmail expeditrice').Trim()
$env:SMARTBANK_SMTP_FROM = $env:SMARTBANK_SMTP_USER
$secret = Read-Host 'Mot de passe application Google' -AsSecureString
$env:SMARTBANK_SMTP_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password.Replace(' ', '').Trim()
$secret.Dispose()
.\scripts\run-backend.cmd -EmailRecovery
```

Les variables SMTP et MySQL doivent etre configurees dans le **meme terminal** que le lancement du backend. Apres un changement de secret, arreter le serveur avec Ctrl+C puis le relancer. En cas de `SMTP_AUTHENTICATION`, verifier le compte expéditeur et regenerer un mot de passe d'application si necessaire. Revoquer tout secret accidentellement partage.

Validation : la reception reelle via Gmail a ete confirmee par l'utilisateur le 27 novembre 2026, apres correction des identifiants SMTP et configuration de l'adresse du client. Cette confirmation ne remplace pas la recette complete des etapes de reinitialisation ci-dessus. Les adresses personnelles, secrets et codes de cette recette restent hors du depot. Les tests automatiques SMTP utilisent uniquement un serveur factice local ; l'ecran de recuperation a aussi ete verifie en mode e-mail.

Sources : [parametres SMTP Gmail](https://developers.google.com/workspace/gmail/imap/imap-smtp), [mots de passe d'application Google](https://support.google.com/accounts/answer/185833).

Reference : [configuration SMTP Eclipse Angus](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html).
