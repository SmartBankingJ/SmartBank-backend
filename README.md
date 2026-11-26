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

Avec `-LocalRecovery`, les codes sont écrits dans `%LOCALAPPDATA%\SmartBankingJ\recovery-outbox\user-<id>.txt`, hors des dépôts. Il s’agit d’une boîte locale pour le développeur, sans envoi d’e-mail/SMS. Sans ce mode ou `SMARTBANK_RECOVERY_OUTBOX`, la récupération répond explicitement qu’elle est indisponible. Ne pas activer cette simulation dans un déploiement réel.

Le lanceur utilise le JDK et les JAR locaux si `SMARTBANK_AUTH_LIBS` désigne un dossier de dépendances, ou si le dossier local préparé `%LOCALAPPDATA%\SmartBankingJ\tools\auth-libs` existe. Sinon il utilise Maven. Les JAR n’entrent pas dans Git. Avec Maven seul : `mvn compile exec:java`.

## Contrat API

Réponses JSON, erreurs `{ "code": "...", "message": "..." }`, en-tête `Cache-Control: no-store`. Le frontend peut changer d’URL avec `SMARTBANK_API_URL` ; une URL distante doit utiliser HTTPS. Le serveur livré est une API locale de développement, pas un déploiement bancaire de production.

| Méthode | Route | Contenu / résultat |
| --- | --- | --- |
| GET | `/health` | Disponibilité du processus, pas une vérification MySQL |
| POST | `/auth/login` | `username`, `password` ; jeton et identité publique, avec `mustChangePassword` |
| GET | `/auth/me` | Identité du porteur du jeton, sauf première connexion restreinte |
| POST | `/auth/logout` | Révocation du jeton ; répétable sans erreur |
| POST | `/auth/change-password` | `currentPassword`, `newPassword` ; nouveau jeton et révocation des anciennes sessions |
| POST | `/auth/forgot-password` | `username` ; réponse neutre, y compris pour un utilisateur inconnu |
| POST | `/auth/reset-password` | `code`, `newPassword` ; code consommé et toutes les sessions révoquées |

Pour les routes authentifiées : `Authorization: Bearer <token>`. Les rôles viennent de la base et ne sont jamais choisis dans la demande de connexion. L’interface desktop actuelle est réservée au rôle CUSTOMER ; les interfaces ADMIN/ADVISOR ne sont pas encore développées.

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
- Fournisseur d'e-mail/SMS si la recuperation reelle est requise ; la boite locale reste une simulation.

References : [MySQL mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html), [privileges MySQL](https://dev.mysql.com/doc/refman/8.0/en/privileges-provided.html).
