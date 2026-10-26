docker run -p 127.0.0.1:8086:8086 --rm -ti google/cloud-sdk gcloud beta emulators bigtable start --host-port=0.0.0.0:8086

export BIGTABLE_EMULATOR_HOST=localhost:8086

cbt -project chatme-local -instance chatme-local \
    createtable registration-sessions "families=S"
    
cbt -project chatme-local -instance chatme-local ls
