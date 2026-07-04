# How to Create and Push the Project Atlas Repository

## 1. Create the GitHub repository

Go to:

https://github.com/new

Repository name:

```text
Project-Atlas
```

Recommended settings:

- Public
- Do not add README
- Do not add .gitignore
- Do not add license

## 2. Copy your actual trading project into this folder

Keep this README and docs, then copy your real Java project files into the repository.

Do not upload API keys, `.env` files, account credentials, broker secrets, or private config files.

## 3. Push from PowerShell

From the Project-Atlas folder:

```powershell
git init
git add .
git commit -m "Initial Project Atlas repository"
git branch -M main
git remote add origin https://github.com/MichaelBennett87/Project-Atlas.git
git push -u origin main
```

If the remote already exists:

```powershell
git remote set-url origin https://github.com/MichaelBennett87/Project-Atlas.git
git push -u origin main
```

## 4. Add it to your portfolio

Use this project link:

```text
https://github.com/MichaelBennett87/Project-Atlas
```
