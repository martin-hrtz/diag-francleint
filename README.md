# Diag francleint

Appli de check-up pour autoradio Android. Lecture seule : elle ne modifie rien sur l'écran.

## Ce qu'elle fait

- Vérifie la vraie version d'Android, la puce, la mémoire, le stockage, le Wi-Fi, la chaleur et l'appli CarPlay.
- Teste chaque bouton du volant, un par un.
- Exporte un rapport `diag-francleint-AAAAMMJJ-HHMM.txt` dans Téléchargements et sur la clé USB branchée.

## Fabriquer l'appli (sans rien installer)

1. Crée un dépôt **public** sur github.com, nommé `diag-francleint`.
2. Dans le dépôt : **Add file → Upload files**, glisse tout le contenu de ce dossier (y compris le dossier `.github`), puis **Commit**.
   Sur Mac, le dossier `.github` est caché : fais `Cmd + Maj + .` dans le Finder pour l'afficher avant de glisser.
3. Onglet **Actions** : la fabrication se lance toute seule (~5 min).
4. Une fois finie, l'appli est en ligne ici :
   `https://github.com/TON-PSEUDO/diag-francleint/releases/latest/download/diag-francleint.apk`

## L'installer sur l'écran

1. Ouvre le lien ci-dessus dans le navigateur de l'écran.
2. Accepte « installer des applis inconnues » quand l'écran le demande.
3. Lance **Diag francleint**, fais le check-up, teste le volant, puis **Exporter mon rapport**.

Pour la désinstaller : Réglages → Applications → Diag francleint → Désinstaller.
