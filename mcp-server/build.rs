use std::io::Result;

fn main() -> Result<()> {
    // Create output directory for generated code
    std::fs::create_dir_all("src/protocol/generated")?;

    // Compile protobuf files
    prost_build::Config::new()
        .out_dir("src/protocol/generated")
        .compile_protos(&["proto/neuralbridge.proto"], &["proto"])?;

    // Tell Cargo to rerun this build script if the proto file changes
    println!("cargo:rerun-if-changed=proto/neuralbridge.proto");

    Ok(())
}
