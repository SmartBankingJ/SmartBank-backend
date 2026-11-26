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
