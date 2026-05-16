package io.github.yusukensanta.parqueteer.cli

object ShellCompletions {

  val bash: String =
    """# bash completion for parqueteer
      |# Install: parqueteer completions bash > /etc/bash_completion.d/parqueteer
      |#      or: eval "$(parqueteer completions bash)"
      |_parqueteer() {
      |  local cur prev words cword
      |  _init_completion || return
      |
      |  local commands="read info write validate convert schema config completions"
      |  local formats="table json csv pretty markdown ndjson"
      |  local compressions="none snappy gzip lzo brotli lz4 zstd"
      |
      |  case "${words[1]}" in
      |    read)
      |      case "$prev" in
      |        --format|-f) COMPREPLY=($(compgen -W "$formats" -- "$cur")) ; return ;;
      |        --max-rows|-n|--columns|-c|--filter) return ;;
      |        *) COMPREPLY=($(compgen -W "--format --max-rows --columns --filter" -- "$cur"))
      |           COMPREPLY+=($(compgen -f -X '!*.parquet' -- "$cur")) ; return ;;
      |      esac ;;
      |    info)
      |      case "$prev" in
      |        --format) COMPREPLY=($(compgen -W "table json" -- "$cur")) ; return ;;
      |        *) COMPREPLY=($(compgen -W "--format --no-schema --no-metadata" -- "$cur"))
      |           COMPREPLY+=($(compgen -f -X '!*.parquet' -- "$cur")) ; return ;;
      |      esac ;;
      |    write)
      |      case "$prev" in
      |        --compression|-c) COMPREPLY=($(compgen -W "$compressions" -- "$cur")) ; return ;;
      |        --input-format) COMPREPLY=($(compgen -W "json csv" -- "$cur")) ; return ;;
      |        --row-group-size) return ;;
      |        *) COMPREPLY=($(compgen -W "--input-format --compression --row-group-size" -- "$cur"))
      |           COMPREPLY+=($(compgen -f -- "$cur")) ; return ;;
      |      esac ;;
      |    validate)
      |      COMPREPLY=($(compgen -W "--verbose" -- "$cur"))
      |      COMPREPLY+=($(compgen -f -X '!*.parquet' -- "$cur")) ; return ;;
      |    convert)
      |      case "$prev" in
      |        --compression) COMPREPLY=($(compgen -W "$compressions" -- "$cur")) ; return ;;
      |        --max-rows|-n) return ;;
      |        *) COMPREPLY=($(compgen -W "--compression --max-rows" -- "$cur"))
      |           COMPREPLY+=($(compgen -f -- "$cur")) ; return ;;
      |      esac ;;
      |    schema)
      |      if [ "${words[2]}" = "diff" ]; then
      |        case "$prev" in
      |          --format) COMPREPLY=($(compgen -W "table json" -- "$cur")) ; return ;;
      |          *) COMPREPLY=($(compgen -W "--format" -- "$cur"))
      |             COMPREPLY+=($(compgen -f -X '!*.parquet' -- "$cur")) ; return ;;
      |        esac
      |      else
      |        COMPREPLY=($(compgen -W "diff" -- "$cur")) ; return
      |      fi ;;
      |    config)
      |      COMPREPLY=($(compgen -W "show validate" -- "$cur")) ; return ;;
      |    completions)
      |      COMPREPLY=($(compgen -W "bash zsh fish" -- "$cur")) ; return ;;
      |    *)
      |      COMPREPLY=($(compgen -W "$commands --verbose --quiet --config --color --version --help" -- "$cur")) ; return ;;
      |  esac
      |}
      |complete -F _parqueteer parqueteer""".stripMargin

  val zsh: String =
    """#compdef parqueteer
      |# zsh completion for parqueteer
      |# Install: parqueteer completions zsh > "${fpath[1]}/_parqueteer"
      |#      or: parqueteer completions zsh > ~/.zfunc/_parqueteer  (add ~/.zfunc to fpath)
      |_parqueteer() {
      |  local state
      |  local -a commands formats compressions
      |
      |  commands=(
      |    'read:Display parquet file content'
      |    'info:Show file metadata and schema information'
      |    'write:Create parquet file from input data'
      |    'validate:Verify parquet file integrity'
      |    'convert:Convert between parquet and other formats'
      |    'schema:Schema inspection commands'
      |    'config:Show or validate configuration'
      |    'completions:Generate shell completion scripts'
      |  )
      |  formats=(table json csv pretty markdown ndjson)
      |  compressions=(none snappy gzip lzo brotli lz4 zstd)
      |
      |  _arguments -C \
      |    '(-v --verbose)'{-v,--verbose}'[Enable verbose output]' \
      |    '(-q --quiet)'{-q,--quiet}'[Suppress non-error output]' \
      |    '--config[Path to configuration file]:config file:_files' \
      |    '--color[Color output mode]:mode:(auto always never)' \
      |    '(-V --version)'{-V,--version}'[Show version information]' \
      |    '(-h --help)'{-h,--help}'[Show help information]' \
      |    '1: :->command' \
      |    '*: :->args' && return 0
      |
      |  case $state in
      |    command)
      |      _describe 'command' commands ;;
      |    args)
      |      case ${words[2]} in
      |        read)
      |          _arguments \
      |            '(-n --max-rows)'{-n,--max-rows}'[Maximum rows]:count' \
      |            '(-c --columns)'{-c,--columns}'[Columns to display]:columns' \
      |            '(-f --filter)'{-f,--filter}'[Filter expression]:expr' \
      |            '--format[Output format]:format:('"${formats[*]}"')' \
      |            ':parquet file:_files -g "*.parquet"' ;;
      |        info)
      |          _arguments \
      |            '--format[Output format]:format:(table json)' \
      |            '--no-schema[Hide schema]' \
      |            '--no-metadata[Hide metadata]' \
      |            ':parquet file:_files -g "*.parquet"' ;;
      |        write)
      |          _arguments \
      |            '--input-format[Input format]:format:(json csv)' \
      |            '(-c --compression)'{-c,--compression}'[Compression]:type:('"${compressions[*]}"')' \
      |            '--row-group-size[Row group size]:size' \
      |            ':input file:_files' \
      |            ':output parquet file:_files -g "*.parquet"' ;;
      |        validate)
      |          _arguments \
      |            '--verbose[Show detailed info]' \
      |            ':parquet file:_files -g "*.parquet"' ;;
      |        convert)
      |          _arguments \
      |            '--compression[Compression]:type:('"${compressions[*]}"')' \
      |            '(-n --max-rows)'{-n,--max-rows}'[Maximum rows]:count' \
      |            ':input file:_files' \
      |            ':output file:_files' ;;
      |        schema)
      |          case ${words[3]} in
      |            diff)
      |              _arguments \
      |                '--format[Output format]:format:(table json)' \
      |                ':file1:_files -g "*.parquet"' \
      |                ':file2:_files -g "*.parquet"' ;;
      |            *)
      |              _values 'subcommand' 'diff[Compare schemas of two parquet files]' ;;
      |          esac ;;
      |        config)
      |          _values 'subcommand' 'show[Display configuration]' 'validate[Validate configuration]' ;;
      |        completions)
      |          _values 'shell' 'bash' 'zsh' 'fish' ;;
      |      esac ;;
      |  esac
      |}
      |_parqueteer "$@"""".stripMargin

  val fish: String =
    """# fish completion for parqueteer
      |# Install: parqueteer completions fish > ~/.config/fish/completions/parqueteer.fish
      |
      |# Top-level commands
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a read       -d 'Display parquet file content'
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a info       -d 'Show file metadata and schema information'
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a write      -d 'Create parquet file from input data'
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a validate   -d 'Verify parquet file integrity'
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a convert    -d 'Convert between parquet and other formats'
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a schema     -d 'Schema inspection commands'
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a config     -d 'Show or validate configuration'
      |complete -c parqueteer -n '__fish_use_subcommand' -f -a completions -d 'Generate shell completion scripts'
      |
      |# Global flags
      |complete -c parqueteer -l verbose -s v -f -d 'Enable verbose output'
      |complete -c parqueteer -l quiet   -s q -f -d 'Suppress non-error output'
      |complete -c parqueteer -l config       -d 'Path to configuration file'
      |complete -c parqueteer -l color   -f -a 'auto always never' -d 'Color output mode'
      |
      |# read
      |complete -c parqueteer -n '__fish_seen_subcommand_from read' -l format  -s f -f -a 'table json csv pretty markdown ndjson' -d 'Output format'
      |complete -c parqueteer -n '__fish_seen_subcommand_from read' -l max-rows -s n -d 'Maximum rows'
      |complete -c parqueteer -n '__fish_seen_subcommand_from read' -l columns  -s c -d 'Columns to display'
      |complete -c parqueteer -n '__fish_seen_subcommand_from read' -l filter        -d 'Filter expression'
      |
      |# info
      |complete -c parqueteer -n '__fish_seen_subcommand_from info' -l format      -f -a 'table json' -d 'Output format'
      |complete -c parqueteer -n '__fish_seen_subcommand_from info' -l no-schema   -f -d 'Hide schema'
      |complete -c parqueteer -n '__fish_seen_subcommand_from info' -l no-metadata -f -d 'Hide metadata'
      |
      |# write
      |complete -c parqueteer -n '__fish_seen_subcommand_from write' -l input-format -f -a 'json csv'                               -d 'Input file format'
      |complete -c parqueteer -n '__fish_seen_subcommand_from write' -l compression  -s c -f -a 'none snappy gzip lzo brotli lz4 zstd' -d 'Compression type'
      |complete -c parqueteer -n '__fish_seen_subcommand_from write' -l row-group-size -d 'Row group size'
      |
      |# validate
      |complete -c parqueteer -n '__fish_seen_subcommand_from validate' -l verbose -f -d 'Show detailed info'
      |
      |# convert
      |complete -c parqueteer -n '__fish_seen_subcommand_from convert' -l compression -f -a 'none snappy gzip lzo brotli lz4 zstd' -d 'Compression type'
      |complete -c parqueteer -n '__fish_seen_subcommand_from convert' -l max-rows -s n -d 'Maximum rows'
      |
      |# schema diff
      |complete -c parqueteer -n '__fish_seen_subcommand_from schema'   -f -a diff -d 'Compare schemas of two parquet files'
      |complete -c parqueteer -n '__fish_seen_subcommand_from diff'     -l format -f -a 'table json' -d 'Output format'
      |
      |# config
      |complete -c parqueteer -n '__fish_seen_subcommand_from config'  -f -a show     -d 'Display configuration'
      |complete -c parqueteer -n '__fish_seen_subcommand_from config'  -f -a validate -d 'Validate configuration'
      |
      |# completions
      |complete -c parqueteer -n '__fish_seen_subcommand_from completions' -f -a 'bash zsh fish' -d 'Shell type'""".stripMargin
}
