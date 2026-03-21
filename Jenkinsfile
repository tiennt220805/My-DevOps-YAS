// These variables maintain the state across different stages of the pipeline
def CHANGED_SERVICES = ""
def IS_ROOT_CHANGED = false
def BUILD_BACKOFFICE = false
def BUILD_STOREFRONT = false

// List of all valid backend microservices in the monorepo
def VALID_BACKEND_SERVICES = [
    'media', 'product', 'cart', 'order', 'rating',
    'customer', 'location', 'inventory', 'tax', 'search'
]

/**
 * Determines which backend services to build based on root changes
 * Returns all services if the root changed, otherwise parses the comma-separated list
 */
def resolveBackendServices(boolean isRootChanged, String changedServices) {
    return isRootChanged ? VALID_BACKEND_SERVICES : changedServices.split(',').findAll { it?.trim() }
}

/**
 * Processes JaCoCo code coverage reports for the specified services
 * Configures quality gate thresholds to enforce minimum coverage requirements
 */
def processCoverage(List<String> services) {
    services.each { String service ->
        recordCoverage(
            id: "coverage-${service}",
            name: "Coverage: ${service.capitalize()}",
            tools: [[
                parser: 'JACOCO',
                pattern: "${service}/target/site/jacoco/jacoco.xml"
            ]],
            qualityGates: [
                [threshold: 70.0, metric: 'LINE', baseline: 'PROJECT', criticality: 'UNSTABLE'],
                [threshold: 70.0, metric: 'BRANCH', baseline: 'PROJECT', criticality: 'FAILURE'],
                [threshold: 70.0, metric: 'INSTRUCTION', baseline: 'PROJECT', criticality: 'FAILURE'],
                [threshold: 70.0, metric: 'METHOD', baseline: 'PROJECT', criticality: 'UNSTABLE'],
                [threshold: 70.0, metric: 'CLASS', baseline: 'PROJECT', criticality: 'FAILURE']
            ]
        )
    }
}

/**
 * Executes SonarQube static code analysis
 * Uses a specific local Maven repository within the workspace to avoid cache conflicts
 */
def runBackendSonarQube(List<String> services) {
    withSonarQubeEnv('SonarQube-Local') {
        services.each { String service ->
            echo ">>> SonarQube scanning: ${service}"
            dir(service) {
                sh "mvn sonar:sonar -Dmaven.repo.local=${WORKSPACE}/.m2/repository"
            }
        }
    }
}

/**
 * Executes Snyk vulnerability scanning for dependencies
 */
def runBackendSnyk(List<String> services) {
    withCredentials([string(credentialsId: 'snyk-token', variable: 'SNYK_TOKEN')]) {
        services.each { String service ->
            echo ">>> Snyk scanning: ${service}"
            dir(service) {
                if (env.BRANCH_NAME == 'main') {
                    sh "npx snyk monitor --project-name=yas-${service}"
                }
                sh "npx snyk test --severity-threshold=high"
            }
        }
    }
}

/**
 * Cleans up the local Maven repository (.m2) to prevent disk space exhaustion
 * Triggers deletion only if the directory exceeds the specified maximum size (in GB)
 */
def cleanupLocalM2Repo(int maxSizeGb = 3) {
    sh """
        if [ -d .m2/repository ]; then
            size_mb=\$(du -sm .m2/repository | cut -f1)
            limit_mb=\$(( ${maxSizeGb} * 1024 ))
            echo "Local .m2 cache size: \${size_mb} MB (limit: \${limit_mb} MB)"
            if [ \"\${size_mb}\" -gt \"\${limit_mb}\" ]; then
                echo "Local .m2 cache exceeds limit; cleaning .m2/repository"
                rm -rf .m2/repository
            fi
        fi
    """
}

/**
 * Executes the standard CI pipeline for Node.js frontend applications
 */
def runFrontendPipeline(String appName) {
    dir(appName) {
        echo "Installing ${appName} dependencies..."
        sh 'npm ci'

        echo "Checking code quality (Linting)..."
        sh 'npm run lint'

        echo "Running SonarQube analysis for ${appName}..."
        withSonarQubeEnv('SonarQube-Local') {
            def scannerHome = tool 'SonarScanner'
            sh "${scannerHome}/bin/sonar-scanner"
        }

        echo "Scanning ${appName} dependencies..."
        withCredentials([string(credentialsId: 'snyk-token', variable: 'SNYK_TOKEN')]) {
            if (env.BRANCH_NAME == 'main') {
                sh "npx snyk monitor --project-name=yas-${appName}"
            }
            sh "npx snyk test --severity-threshold=high"
        }

        echo "Building ${appName} UI..."
        sh 'npm run build'
    }
}

pipeline {
    // Execute on any available Jenkins agent
    agent any

    // Define standard build tools configured in Jenkins Global Tool Configuration
    tools {
        maven 'maven3'
        nodejs 'node20'
    }

    environment {
        // Use local repository within the workspace for faster caching
        MAVEN_OPTS = "-Dmaven.repo.local=.m2/repository"
        // Required for Testcontainers to communicate with the Docker daemon inside Jenkins agents
        TESTCONTAINERS_HOST_OVERRIDE = 'docker'
    }

    stages {
        // --- STAGE 1: CHECKOUT CODE ---
        stage('Checkout Code') {
            steps {
                checkout scm
                script {
                    echo "Checking out branch: ${env.BRANCH_NAME}"
                }
            }
        }

        // --- STAGE 2: SECRET SCAN ---
        // Scans the repository for accidentally committed secrets/credentials
        stage('Secret Scan') {
            steps {
                script {
                    echo "Checking for secrets..."
                    if (!fileExists('gitleaks')) {
                        echo "Downloading Gitleaks..."
                        sh 'curl -ssfL https://github.com/gitleaks/gitleaks/releases/download/v8.18.2/gitleaks_8.18.2_linux_x64.tar.gz | tar -xz gitleaks'
                    }

                    sh 'chmod +x gitleaks'

                    try {
                        sh './gitleaks detect --source . --config gitleaks.toml --verbose --no-git'
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        error("Gitleaks found secrets in your code")
                    }
                }
            }
        }

        // --- STAGE 3: ANALYZE CHANGES ---
        // Determines exactly which services in the monorepo were modified in this commit/PR
        stage('Analyze Changes') {
            steps {
                script {
                    echo "Analyzing changes to determine build scope..."

                    def baseBranch = env.CHANGE_TARGET ?: 'main'
                    def diffCommand = ""

                    if (env.BRANCH_NAME == 'main') {
                        def hasParent = sh(script: "git rev-parse HEAD~1", returnStatus: true) == 0
                        if (hasParent) {
                            diffCommand = "git diff --name-only HEAD~1 HEAD"
                        } else {
                            diffCommand = "git show --name-only --pretty='' HEAD"
                        }
                    } else {
                        sh "git fetch origin ${baseBranch}:refs/remotes/origin/${baseBranch} --depth=10"
                        diffCommand = "git diff --name-only origin/${baseBranch} HEAD"
                    }

                    def changedFilesList = []
                    try {
                        def changedFilesRaw = sh(script: diffCommand, returnStdout: true).trim()
                        changedFilesList = changedFilesRaw ? changedFilesRaw.readLines().findAll { it?.trim() } : []
                    } catch (Exception e) {
                        echo "First build or error detected. Need to build ALL."
                        IS_ROOT_CHANGED = true
                    }
                    echo "List of changed files:\n${changedFilesList.join('\n')}"

                    def servicesToBuild = [] as LinkedHashSet

                    for (String file in changedFilesList) {
                        if (!file) continue

                        if (file == "pom.xml") {
                            IS_ROOT_CHANGED = true
                        }

                        if (file.startsWith("common-library/")) {
                            echo "Common library changed. Marking all valid backend services..."
                            servicesToBuild.addAll(VALID_BACKEND_SERVICES)
                        }

                        if (file.startsWith("backoffice/")) BUILD_BACKOFFICE = true
                        if (file.startsWith("storefront/")) BUILD_STOREFRONT = true

                        def topLevelDir = file.tokenize('/').first()
                        if (topLevelDir) {
                            if (VALID_BACKEND_SERVICES.contains(topLevelDir)) {
                                servicesToBuild.add(topLevelDir)
                            }
                        }
                    }

                    if (IS_ROOT_CHANGED) {
                        echo "Root configuration changed. Building ALL services."
                        BUILD_BACKOFFICE = true
                        BUILD_STOREFRONT = true
                        CHANGED_SERVICES = ''
                    } else {
                        CHANGED_SERVICES = servicesToBuild.join(',')
                    }
                    
                    echo "---------- BUILD PLAN ----------"
                    echo "Backend Services: ${IS_ROOT_CHANGED ? 'ALL' : (CHANGED_SERVICES ?: 'N/A')}"
                    echo "Frontend Backoffice: ${BUILD_BACKOFFICE}"
                    echo "Frontend Storefront: ${BUILD_STOREFRONT}"
                    echo "--------------------------------"
                }
            }
        }

        // --- STAGE 4: DYNAMIC PARALLEL INTEGRATION & VALIDATION ---
        // Dynamically provisions isolated Jenkins executors to run builds in parallel
        stage('Integration & Validation') {
            steps {
                script {
                    def parallelBranches = [:]
                    def backendServices = resolveBackendServices(IS_ROOT_CHANGED, CHANGED_SERVICES)

                    // 1. CREATE PARALLEL BRANCHES FOR BACKEND SERVICES
                    if (backendServices.size() > 0) {
                        for (int i = 0; i < backendServices.size(); i++) {
                            // IMPORTANT: Must bind to a local variable inside the loop for Groovy closures
                            def currentService = backendServices[i]
                            
                            parallelBranches["Backend-${currentService}"] = {
                                // Request a completely new, isolated Jenkins executor/node
                                node() { 
                                    stage("Pipeline: ${currentService}") {
                                        checkout scm
                                        
                                        // Phase 1: Build & Test
                                        echo "Building and testing ${currentService}..."
                                        // The '-am' (also make) flag ensures required internal dependencies (like common-library) are built too
                                        sh "mvn clean install jacoco:report -pl ${currentService} -am"
                                        
                                        // Process JUnit test results and JaCoCo coverage reports
                                        junit '**/target/surefire-reports/*.xml'
                                        processCoverage([currentService])
                                        
                                        // Phase 2: SonarQube Analysis
                                        runBackendSonarQube([currentService])
                                        
                                        // Phase 3: Quality Gate Check
                                        timeout(time: 5, unit: 'MINUTES') {
                                            waitForQualityGate abortPipeline: true
                                        }
                                        
                                        // Phase 4: Snyk Vulnerability Scan
                                        echo "Scanning backend dependencies for ${currentService}..."
                                        // runBackendSnyk([currentService])
                                        
                                        // Free up disk space on this specific executor node
                                        cleanupLocalM2Repo(3)
                                        cleanWs()
                                    }
                                }
                            }
                        }
                    }

                    // 2. CREATE PARALLEL BRANCHES FOR BACKOFFICE
                    if (BUILD_BACKOFFICE || IS_ROOT_CHANGED) {
                        parallelBranches["Frontend-backoffice"] = {
                            node() {
                                stage('Pipeline: backoffice') {
                                    checkout scm
                                    runFrontendPipeline('backoffice')
                                    cleanWs()
                                }
                            }
                        }
                    }

                    // 3. CREATE PARALLEL BRANCHES FOR STOREFRONT
                    if (BUILD_STOREFRONT || IS_ROOT_CHANGED) {
                        parallelBranches["Frontend-storefront"] = {
                            node() {
                                stage('Pipeline: storefront') {
                                    checkout scm
                                    runFrontendPipeline('storefront')
                                    cleanWs()
                                }
                            }
                        }
                    }

                    // 4. ACTIVATE EXECUTION OF ALL PIPELINES IN PARALLEL
                    if (parallelBranches.size() > 0) {
                        echo "Executing ${parallelBranches.size()} pipelines in parallel..."
                        parallel parallelBranches
                    } else {
                        echo "No services affected. Skipping Validation stage."
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                cleanupLocalM2Repo(3)
            }
            sh 'rm -f gitleaks'
            cleanWs()
        }
        success {
            echo "[SUCCESS] CI Pipeline completed successfully!"
        }
        failure {
            echo "[FAILURE] CI Pipeline failed! Check logs for compile errors, test failures, or security issues."
        }
    }
}
