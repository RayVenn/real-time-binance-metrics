# Infrastructure Setup Guide

Step-by-step instructions for deploying the portfolio optimizer to AWS EKS with ArgoCD.

## Prerequisites

- AWS CLI v2 configured with admin credentials
- [eksctl](https://eksctl.io) installed
- kubectl installed
- Helm v3 installed
- GitHub repo with Actions enabled

## 1. Create EKS Cluster

```bash
eksctl create cluster \
  --name portfolio-optimizer \
  --region us-east-1 \
  --version 1.29 \
  --nodegroup-name workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 2 \
  --nodes-max 4 \
  --managed
```

This takes ~15 minutes. Verify with:

```bash
kubectl get nodes
```

## 2. Create ECR Repository

```bash
aws ecr create-repository \
  --repository-name portfolio-optimizer \
  --region us-east-1 \
  --image-scanning-configuration scanOnPush=true
```

Note the repository URI from the output (format: `<ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/portfolio-optimizer`). Update `helm/portfolio-optimizer/values.yaml` with this URI in the `image.repository` field.

## 3. Set Up OIDC for GitHub Actions (no long-lived AWS keys)

### 3a. Create the OIDC identity provider (one-time per AWS account)

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

### 3b. Create IAM trust policy

Save as `trust-policy.json` (replace `<ACCOUNT_ID>` and `<OWNER>`):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:<OWNER>/portfolio-optimizer:*"
        }
      }
    }
  ]
}
```

### 3c. Create the IAM role and attach ECR permissions

```bash
aws iam create-role \
  --role-name github-actions-ecr \
  --assume-role-policy-document file://trust-policy.json

aws iam attach-role-policy \
  --role-name github-actions-ecr \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser
```

## 4. Install ArgoCD on EKS

```bash
kubectl create namespace argocd

kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Wait for all pods to be ready
kubectl wait --for=condition=Ready pods --all -n argocd --timeout=300s

# Get the initial admin password
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d && echo

# Port-forward to access the UI
kubectl port-forward svc/argocd-server -n argocd 8080:443
```

Open https://localhost:8080 in your browser. Login with username `admin` and the password from above.

### Install ArgoCD CLI (optional)

```bash
brew install argocd

argocd login localhost:8080 --username admin --password <PASSWORD> --insecure
argocd account update-password
```

## 5. Connect GitHub Repo to ArgoCD

```bash
# Option A: HTTPS with personal access token
argocd repo add https://github.com/RayVenn/portfolio-optimizer.git \
  --username RayVenn \
  --password <GITHUB_PAT>

# Option B: SSH key
argocd repo add git@github.com:RayVenn/portfolio-optimizer.git \
  --ssh-private-key-path ~/.ssh/id_ed25519
```

## 6. Create the Kubernetes Secret for API_KEY

```bash
kubectl create namespace portfolio-optimizer

kubectl create secret generic portfolio-optimizer-secret \
  --from-literal=api-key=<YOUR_PRODUCTION_API_KEY> \
  -n portfolio-optimizer
```

## 7. Configure GitHub Repository Secrets

Go to your GitHub repo → Settings → Secrets and variables → Actions. Add:

| Secret Name      | Value                        |
|------------------|------------------------------|
| `AWS_ACCOUNT_ID` | Your 12-digit AWS account ID |

No AWS access keys needed — OIDC handles authentication.

## 8. Deploy the ArgoCD Application

```bash
kubectl apply -f argocd/application.yaml
```

ArgoCD will now:
1. Clone the repo
2. Render the Helm chart from `helm/portfolio-optimizer/`
3. Deploy to the `portfolio-optimizer` namespace on EKS
4. Automatically sync on every Git change (polls every 3 minutes)

## 9. Verify

```bash
# Check ArgoCD sync status
argocd app get portfolio-optimizer

# Check pods
kubectl get pods -n portfolio-optimizer

# Port-forward to test locally
kubectl port-forward svc/portfolio-optimizer 8000:80 -n portfolio-optimizer

# Test health
curl http://localhost:8000/api/v1/health
```

## Rollback

```bash
# Option 1: Git revert (recommended — maintains GitOps)
git revert <bad-commit>
git push origin main
# CI builds new image, ArgoCD syncs automatically

# Option 2: ArgoCD UI
# Click History → select previous revision → Sync
```

## Cleanup

```bash
# Delete ArgoCD application (also deletes deployed resources)
kubectl delete -f argocd/application.yaml

# Delete ArgoCD
kubectl delete namespace argocd

# Delete EKS cluster
eksctl delete cluster --name portfolio-optimizer --region us-east-1
```
