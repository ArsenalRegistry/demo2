def label = "Appdu-${UUID.randomUUID().toString()}"


podTemplate(label: label, serviceAccount: 'default', namespace: 'appdu-demo3',
    containers: [
        containerTemplate(name: 'build-tools', image: 'ktis-bastion01.container.ipc.kt.com:5000/alpine/build-tools:v2.0', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
        containerTemplate(name: 'node', image: 'ktis-bastion01.container.ipc.kt.com:5000/admin/node:latest', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
        containerTemplate(name: 'jnlp', image: 'ktis-bastion01.container.ipc.kt.com:5000/jenkins/jnlp-slave:2.426.3', args: '${computer.jnlpmac} ${computer.name}')
    ],
    volumes: [
        hostPathVolume(hostPath: '/etc/mycontainers', mountPath: '/var/lib/containers'),
        // nfsVolume(mountPath: '/home/jenkins', serverAddress: '10.217.67.145', serverPath: '/data/nfs/devops/jenkins-slave-pv', readOnly: false)
        ]
    ) {
    node(label) {

        library 'pipeline-lib'

        try {

            // freshStart 
            if ( params.freshStart ) {
                container('build-tools'){
                    // remove previous working dir
                    print "freshStart... clean working directory ${env.JOB_NAME}"
                    sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
                }
            }


            // Initialize - 변수 설정. properties 파일에서 escape 처리가 필요한 변수만 기록하고 처리함.

            // Namespace - Kubernetes 사용자 작업 namespace 명칭
            def namespace           = "appdu-demo3"
            // ProjectName - Git Project Name
            def projectName         = "frontend-vue"
            // BrankName - Git Branch 선택된 명칭(origin이 제거된 순수 branch 명칭)
            def branchName          = getBranchName(params.branchName)
            // TagName - Git, Docker Image TAG 명칭
            def tagName             = new Date().format("yy.MM.dd.HHmm", TimeZone.getTimeZone('Asia/Seoul')) + "-" + branchName
            // Docker Registry - Docker 이미지 저장 Registry
            def dockerRegistry      = "https://" + "image-registry.openshift-image-registry.svc:5000"
            // Image - Docker 이미지 명칭
            def imageName           = "image-registry.openshift-image-registry.svc:5000/appdu-demo3/frontend-vue"
            // Maven Setting
            def mvnSettings         = "${env.WORKSPACE}/devops/jenkins/settings.xml"

            // Jenkins System Environments
            def jobName             = "${env.JOB_NAME}"
            def workspace           = "${env.WORKSPACE}"


            stage('Get Source') {

                sh """
                    git config --global user.email "admin@example.com"
                    git config --global user.name "Administrator"
                    git config --global credential.helper cache
                    git config --global push.default simple

                """
                git url: "http://172.30.216.150/appdu-demo3/frontend-vue.git",
                    credentialsId: 'appdu-gitlab-kt-credential',
                    branch: "${branchName}"
            }


            stage('Common - preWork') {
                gl_preHandler(namespace: namespace, projectName: projectName, branchName: branchName, tagName: tagName, dockerRegistry: dockerRegistry, imageName: imageName, jobName: jobName, workspace: workspace)
            }



            if (params.sparrowEnable) {
                stage('Sparrow') {
                    container('sparrow') {
                        try {
                            def sparrowProjectKey = namespace + "-" + projectName
                            print "Sparrow Project Name : ${sparrowProjectKey}"
                            gl_SparrowRunMD(sparrowProjectKey, "appdu")
                        } catch (e) {
                            print "Sparrow Error :: " + e.toString()+"**"
                            currentBuild.result = "UNSTABLE"
                        }
                    }
                }
            }


            stage('NPM Build') {
                container('node') {
                        sh "npm config set registry http://10.217.59.89/nexus3/repository/npm-group/"
                        sh "npm install --sass-binary-site=http://10.217.59.89/nexus3/repository/arsenal-raw-hosted/npm-libraries/node-sass"
                        env.NODE_ENV = "production" 
                        sh "npm run build"
                        sh "rm -rf node_modules"
                }
            }
            
            // if (params.unitTestEnable){
            //     stage('unit test'){
            //         container('node') {
            //             sh "npm test"
            //         }
            //     }
            // }

            stage('Build Docker image') {
                container('build-tools') {

                    withCredentials([usernamePassword(credentialsId: 'cluster-registry-credentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) { 
                        sh "podman login -u ${USERNAME} -p ${PASSWORD} ${dockerRegistry}  --tls-verify=false"
                        sh "podman build -t ${imageName}:${tagName} -f devops/jenkins/Dockerfile . --tls-verify=false"
                        sh "podman push ${imageName}:${tagName} --tls-verify=false"
                        
                        sh "podman tag ${imageName}:${tagName} ${imageName}:latest"
                        sh "podman push ${imageName}:latest --tls-verify=false"
                    }
                }
            }


            stage( 'Helm lint' ) {
                container('build-tools') {
                    dir('devops/helm/frontend-vue'){
                        sh """
                        # initial helm
                        # central helm repo can't connect
                        # setting stable repo by local repo
                        # helm init --client-only --stable-repo-url "http://127.0.0.1:8879/charts" --skip-refresh
                        helm lint --namespace appdu-demo3 .
                        """
                        
                    }
                }
            }


            stage('Tagging Version') {

                try {
                    
                    withCredentials([
                        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'appdu-gitlab-kt-credential', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD']
                        ]) {
                        sh("git config credential.username ${env.GIT_USERNAME}")
                        sh("git config credential.helper '!echo password=\$GIT_PASSWORD; echo'")
                        sh("GIT_ASKPASS=true git push origin --tags")
                    
                        sh """
                            sed -i "s/^version.*/version: ${tagName}/g" devops/helm/frontend-vue/values.yaml

                            git commit -am "helm version changed"
                            git tag -a ${tagName} -m "Appdu's Jenkins added it."
                            git push --tags
                        """
                    }
                } finally {
                    sh("git config --unset credential.username")
                    sh("git config --unset credential.helper")
                }
                
            }


            stage('Common - postWork') {
                gl_preHandler(namespace: namespace, projectName: projectName, branchName: branchName, tagName: tagName, dockerRegistry: dockerRegistry, imageName: imageName, jobName: jobName, workspace: workspace)
            }




        } catch(e) {
            container('build-tools'){
                print "Clean up ${env.JOB_NAME} workspace..."
                sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
            }


            currentBuild.result = "FAILED"
            
            print " **Error :: " + e.toString()+"**"
        }
    }
}


String getBranchName(branch) {
    branchTemp=sh returnStdout:true ,script:"""echo "$branch" |sed -E "s#origin/##g" """
    if(branchTemp){
        branchTemp=branchTemp.trim()
    }
    return branchTemp
}
