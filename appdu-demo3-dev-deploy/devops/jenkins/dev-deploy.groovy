def label = "Appdu-${UUID.randomUUID().toString()}"


podTemplate(label: label, serviceAccount: 'default', namespace: 'appdu-demo3',
    containers: [
        containerTemplate(name: 'build-tools', image: 'ktis-bastion01.container.ipc.kt.com:5000/alpine/build-tools:v2.0', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
        containerTemplate(name: 'jnlp', image: 'ktis-bastion01.container.ipc.kt.com:5000/jenkins/jnlp-slave:2.426.3', args: '${computer.jnlpmac} ${computer.name}')
    ],
    volumes: [
        hostPathVolume(hostPath: '/etc/mycontainers', mountPath: '/var/lib/containers')
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
            def projectName         = "appdu-demo3-dev-deploy"
            // Helm Release Name - Git Project Name
            def releaseName         = "appdu-demo3-dev-deploy"
            // BrankName - Git Branch 선택된 명칭(origin이 제거된 순수 branch 명칭)
            def branchName          = getBranchName(params.branchName)
            // TagName - Git, Docker Image TAG 명칭
            def tagName             = new Date().format("yy.MM.dd.HHmm", TimeZone.getTimeZone('Asia/Seoul')) + "-" + branchName

            // Jenkins System Environments
            def jobName             = "${env.JOB_NAME}"
            def workspace           = "${env.WORKSPACE}"

            // Helm Addtional Flag - subChart Version | force ...
            def additionalFlag      = ""


            stage('Get Source') {

                // remove previous working dir
                sh "rm -fr main"

                dir('main'){
                    git url:  "http://172.30.216.150/appdu-demo3/appdu-demo3-dev-deploy.git",
                    credentialsId: 'appdu-gitlab-kt-credential',
                    branch: "${branchName}"
                }
            }


            stage('Common - preWork') {
                gl_preHandler(namespace: namespace, projectName: projectName, releaseName: releaseName, branchName: branchName, tagName: tagName, jobName: jobName, workspace: workspace)
            }


            stage('Setting git repo') {
                def lines
                dir('main'){
                    def projectFile= "devops/projects.lst"
                    lines = readFile projectFile
                }
                lines.split('\n').each {
                    //substring the project name
                    subProjectName = it.tokenize('/')[-1].tokenize('.')[-2]
                    print "subProjectName name is " + subProjectName

                    // remove previous git cloned project dir
                    sh "rm -fr ${subProjectName}"

                    def projectVersion = params."${subProjectName}"
                    if ( !projectVersion || projectVersion == "null" ) {
                        println "${subProjectName} release version not selected."
                        throw new Exception("Project release version not selected. build again with project release version.")
                    }

                    dir(subProjectName){
                        def projectParams = "refs/tags/"+projectVersion
                        echo projectParams
                        checkout ([$class: 'GitSCM',
                            branches: [ [name: projectParams ] ],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [],
                            gitTool: 'Default',
                            submoduleCfg: [],
                            userRemoteConfigs:[ [
                                credentialsId: 'appdu-gitlab-kt-credential',
                                refspec: '+refs/tags/*:refs/remotes/origin/tags/*',
                                url: it
                            ] ],
                            credentialsId: 'appdu-gitlab-kt-credential'
                        ])

                        //helm repoository move
                        sh "cp -r devops/helm/$subProjectName ${env.WORKSPACE}/main/devops/helm/appdu-demo3-dev-deploy/charts/"

                    }//project dir
                    // set values.yaml
                    dir("main/devops/helm/appdu-demo3-dev-deploy"){
                        sh """
                            # delete/insert project version to values.yaml
                            sed -i 's/^${subProjectName}.version.*//g' values.yaml
                            sed -i '1i\\${subProjectName}.version: ${projectVersion}' values.yaml
                        """
                    }
                }//project loop
            }//stage


            stage('Get Latest Version'){
                container('build-tools'){
                    dir('main'){
                        projectNameList=sh returnStdout: true, script: "cat devops/projects.lst|sed 's#.*/##'|sed 's/.git\$//'"
                        echo projectNameList

                        withCredentials([
                        [$class: 'UsernamePasswordMultiBinding', credentialsId: 'cluster-registry-credentials', usernameVariable:'USERNAME',passwordVariable:'PASSWORD']
                        ]) {
                            projectNameList.split().each{ projectNameTemp ->
                                // curl 명령을 사용하여 Docker 이미지 스트림의 최신 버전 이미지의 해시(digest)를 가져옵니다
                                digestTemp=sh returnStdout: true, script: """
                                 curl -k -X GET -H 'Authorization: Bearer ${PASSWORD}' https://api.openshift-apiserver.svc:443/apis/image.openshift.io/v1/namespaces/appdu-demo3/imagestreams/${projectNameTemp} | jq '.status.tags[] | select(.tag=="latest").items | max_by(.created).image' | sed -e 's/"//g'
                                """
                                digestTemp=digestTemp.trim()
                                // 각 프로젝트의 버전을 해당 digest로 설정하여 Helm 차트를 업데이트할 때 사용될 것입니다.
                                additionalFlag= additionalFlag + " --set ${projectNameTemp}.version='${digestTemp}'"
                            }
                            echo additionalFlag
                        }

                        
                    }
                }
            }


            stage('Deploy to Cluster') {

                isForceUpgrade=params.isForceUpgrade
                if( isForceUpgrade ){
                    additionalFlag="--force"
                }

                globalIstioAutoInject=params.globalIstioAutoInject
                if( globalIstioAutoInject ) {
                    additionalFlag= additionalFlag + " --set global.istio.enabled=true"
                } else {
                    additionalFlag= additionalFlag + " --set global.istio.enabled=false"
                }

                container('build-tools') {
                    dir("main/devops/helm/appdu-demo3-dev-deploy") {

                        sh """
                        # initial helm
                        # central helm repo can't connect
                        # setting stable repo by local repo
                        # helm init --client-only --stable-repo-url "http://127.0.0.1:8879/charts" --skip-refresh
                        helm3 lint --namespace appdu-demo3 .
                        helm3 upgrade --install --namespace appdu-demo3 ${releaseName} ${additionalFlag} .
                        """

                    }
                    // log file for the notification
                    sh "helm3 status ${releaseName} -n appdu-demo3 > helm-status.text"

                }
            }

            stage('Tagging Version') {
                dir('main'){
                    try {

                        withCredentials([
                            [$class: 'UsernamePasswordMultiBinding', credentialsId: 'appdu-gitlab-kt-credential', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD']]) 
                            {
                                sh("""git config user.email "admin@example.com" """)
                                sh("""git config user.name "Administrator" """)
                                sh("git config credential.username ${env.GIT_USERNAME}")
                                sh("git config credential.helper '!echo password=\$GIT_PASSWORD; echo'")
                                sh("GIT_ASKPASS=true git push origin --tags")

                                sh """
                                    git add . -A
                                    git commit -am "helm version changed"
                                    git tag -a ${tagName} -m "jenkins added"
                                    git push --tags
                                """
                            }
                    } finally {
                        sh("git config --unset credential.username")
                        sh("git config --unset credential.helper")
                    }
                }

            }
            

            stage('Common - postWork') {
                gl_preHandler(namespace: namespace, projectName: projectName, releaseName: releaseName, branchName: branchName, tagName: tagName, jobName: jobName, workspace: workspace)
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
