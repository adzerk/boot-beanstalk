(ns adzerk.boot-beanstalk
  (:require
   [clojure.java.io :as io]
   [boot.pod        :as pod]
   [boot.core       :as boot]
   [boot.util       :as util]
   [boot.file       :as file]))

(def ^:private pod-deps
  '[[adzerk/lein-beanstalk "0.2.8"]
    [org.antlr/ST4 "4.0.8"]])

(defn- make-pod
  []
  (-> (boot/get-env)
      (update-in [:dependencies] (fnil into []) pod-deps)
      pod/make-pod
      future))

(def ^:private pod (delay @(make-pod)))

(boot/deftask dockerrun
  "Create Dockerrun.aws.json file.

  The default log directory is /var/log/nginx."

  [v volumes HOST:CTNR {str str} "The map of host to container directories."
   l logging PATH      str       "The log directory to map in the container."]

  (let [tgt (boot/temp-dir!)]
    (boot/with-pre-wrap fileset
      (let [out   (io/file tgt "Dockerrun.aws.json")
            ->vol (fn [[h c]] {"HostDirectory" h "ContainerDirectory" c})]
        (spit out (boot/json-generate {"AWSEBDockerrunVersion" "1"
                                       "Volumes" (mapv ->vol volumes)
                                       "Logging" (or logging "/var/log/nginx")}))
        (-> fileset (boot/add-resource tgt) boot/commit!)))))

(boot/deftask beanstalk
  "AWS Elastic Beanstalk environment management."

  [f file PATH          str  "The (optional) name of the deployable artifact in the fileset (defaults to project.war)."
   A access-key KEY     str  "The AWS API access key."
   S secret-key KEY     str  "The AWS API secret key."
   n name NAME          str  "The application name."
   D description DESC   str  "The application description."
   v version VER        str  "The application version."
   a aws-region REGION  str  "The AWS region where app is deployed."
   b beanstalk-envs CLJ edn  "The edn map of beanstalk environments."
   B bucket BUCKET      str  "The S3 bucket where app is uploaded."
   e env NAME           str  "The (optional) environment name."
   s stack-name STACK   str  "The (optional) beanstalk solution stack name."
   l list-stacks        bool "Print a list of available solution stack names."
   c clean              bool "Clean out old versions, except the ones currently deployed."
   d deploy             bool "Deploy the current project to Amazon Elastic Beanstalk."
   i info               bool "Print info about the project on Amazon Elastic Beanstalk."
   t terminate          bool "Terminate the environment for the current project on Amazon Elastic Beanstalk."]

  (if-not (or clean deploy info terminate list-stacks)
    (beanstalk :help true)
    (let [p @pod]
      (boot/with-pre-wrap fileset
        (let [[file-path] (boot/by-name [(or file "project.war")] (boot/output-files fileset))
              file (.getAbsolutePath (boot/tmp-file file-path))]
          (pod/with-call-in p
            (adzerk.boot-beanstalk.impl/beanstalk ~(assoc *opts* :file file))))
        fileset))))

(boot/deftask eb-war
  "Create war file for web deployment on elastic beanstalk."

  [f file PATH str "The target war file name."]

  (let [tgt (boot/tmp-dir!)]
    (boot/with-pre-wrap fileset
      (boot/empty-dir! tgt)
      (let [warname (or file "project.war")
            warfile (io/file tgt warname)
            inf?    #{"META-INF" "WEB-INF"}]
        (let [->war   #(let [r    (boot/tmp-path %)
                             r'   (file/split-path r)
                             path (cond
                                    (.endsWith r ".jar") ["WEB-INF" "lib" (last r')]
                                    (#{"cron.yaml" ".ebextensions"} (first r')) r'
                                    :else (into ["WEB-INF" "classes"] r'))]
                         (if (inf? (first r')) r (.getPath (apply io/file path))))
              entries (boot/output-files fileset)
              index   (mapv (juxt ->war #(.getPath (boot/tmp-file %))) entries)]
          (util/info "Writing %s...\n" (.getName warfile))
          (pod/with-call-worker
            (boot.jar/spit-jar! ~(.getPath warfile) ~index {} nil))
          (-> fileset (boot/add-resource tgt) boot/commit!))))))

(defn- copy [tf dir]
  (let [f (boot/tmp-file tf)]
    (util/with-let [to (doto (io/file dir (:path tf)) io/make-parents)]
      (io/copy f to))))

(boot/deftask template
  "Perform text substitution on files in the fileset using StringTemplate.  For instance, with a file foo/bar.txt in the fileset with this content:

       My name is $name$

   And this task used like this:

     (template :paths [\"foo/bar.txt\"] {\"name\" \"Barney\"})

   foo/bar.txt will appear in the target directory with this content:

       My name is Barney"

  [p paths PATH    #{str}     "FileSet root-relative paths of input file(s) to perform substitutions on."
   s subs  OLD=NEW  {str str} "String substitutions to perform on the file(s)."]

  (let [p   @pod
        tgt (boot/tmp-dir!)]
    (boot/with-pre-wrap [fs]
      (boot/empty-dir! tgt)
      (if-let [files (and subs (seq (filter (comp (set paths) boot/tmp-path) (boot/input-files fs))))]
        (do (doseq [f files :let [subf (copy f tgt)
                                  txt  (slurp subf)
                                  path (boot/tmp-path f)]]
              (util/info "Performing substitutions on %s\n" path)
              (spit subf (pod/with-call-in p (adzerk.boot-beanstalk.impl/render-template ~txt ~path ~subs))))
            (-> fs (boot/rm files) (boot/add-resource tgt) boot/commit!))
        fs))))
