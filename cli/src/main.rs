use clap::Parser;

// Chunk processing repeatedly allocates and frees; the system allocator returns pages
// and faults them back in, inflating sys time. Use mimalloc to pool pages.
#[cfg(not(feature = "memstats"))]
#[global_allocator]
static ALLOC: mimalloc::MiMalloc = mimalloc::MiMalloc;

#[cfg(feature = "memstats")]
#[global_allocator]
static ALLOC: uika::memstats::CountingAlloc = uika::memstats::CountingAlloc;

fn main() {
    let cli = uika::cli::Cli::parse();
    match uika::run(cli) {
        Ok(code) => std::process::exit(code),
        Err(e) => {
            eprintln!("error: {e:#}");
            std::process::exit(2);
        }
    }
}
